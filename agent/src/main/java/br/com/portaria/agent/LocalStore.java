package br.com.portaria.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Armazenamento local persistente (SQLite): política, fila de eventos (outbox), comandos já vistos e metadados.
 * A quota local de uma credencial é derivada da própria fila: usos ainda não sincronizados. Assim, depois do
 * ACK da central o uso passa a vir dentro da política e nada é contado duas vezes.
 * Se o arquivo estiver corrompido, é posto de lado e recriado (e isso fica visível em {@link #recoveredFromCorruption}).
 * Se o disco/limite de páginas esgotar, as operações lançam {@link StorageException} e o chamador falha fechado.
 */
public final class LocalStore implements AutoCloseable {

    /** Falha de armazenamento (disco cheio, I/O, arquivo ilegível). */
    public static final class StorageException extends Exception {
        StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Política persistida, ainda por verificar (assinatura e validade são checadas por quem a usa). */
    public record StoredPolicy(String json, String signature) {
    }

    private static final Logger log = LoggerFactory.getLogger(LocalStore.class);

    private final Connection db;
    private final boolean recovered;

    private LocalStore(Connection db, boolean recovered) {
        this.db = db;
        this.recovered = recovered;
    }

    public static LocalStore open(Path file) throws StorageException {
        try {
            Files.createDirectories(file.getParent());
            return init(file, false);
        } catch (SQLException | IOException first) {
            log.error("Local store unreadable ({}); moving it aside and starting a new one", first.getMessage());
            try {
                if (Files.exists(file)) {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()));
                }
                return init(file, true);
            } catch (SQLException | IOException second) {
                throw new StorageException("Cannot create local store", second);
            }
        }
    }

    private static LocalStore init(Path file, boolean recovered) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equals(rs.getString(1))) {
                    throw new SQLException("integrity_check failed");
                }
            }
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS policy(id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL, "
                    + "signature TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS outbox(event_id TEXT PRIMARY KEY, type TEXT NOT NULL, "
                    + "credential_id TEXT, payload TEXT NOT NULL, created_at TEXT NOT NULL, ready INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS seen_commands(command_id TEXT PRIMARY KEY, at TEXT NOT NULL)");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return new LocalStore(c, recovered);
    }

    public boolean recoveredFromCorruption() {
        return recovered;
    }

    // ---- política ----

    public synchronized Optional<StoredPolicy> loadPolicy() throws StorageException {
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT json, signature FROM policy")) {
            return rs.next() ? Optional.of(new StoredPolicy(rs.getString(1), rs.getString(2))) : Optional.empty();
        } catch (SQLException e) {
            throw new StorageException("loadPolicy", e);
        }
    }

    public synchronized void savePolicy(String json, String signature) throws StorageException {
        try (PreparedStatement p = db.prepareStatement(
                "INSERT INTO policy(id, json, signature) VALUES (1, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET json = excluded.json, signature = excluded.signature")) {
            p.setString(1, json);
            p.setString(2, signature);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("savePolicy", e);
        }
    }

    // ---- fila de eventos ----

    /** Usos ainda não sincronizados desta credencial (a quota local). */
    public synchronized int localUses(UUID credentialId) throws StorageException {
        try (PreparedStatement p = db.prepareStatement("SELECT count(*) FROM outbox WHERE credential_id = ? AND type IN "
                + "('OFFLINE_ENTRY_GRANTED', 'OFFLINE_ENTRY_UNCERTAIN')")) {
            p.setString(1, credentialId.toString());
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new StorageException("localUses", e);
        }
    }

    /** Reserva o uso e grava o evento como "não pronto": só vai à central depois de {@link #finalizeReservation}. */
    public synchronized void reserve(OutboxEvent e) throws StorageException {
        insert(e, 0);
    }

    public synchronized void finalizeReservation(UUID eventId) throws StorageException {
        update("UPDATE outbox SET ready = 1 WHERE event_id = ?", eventId);
    }

    public synchronized void rollbackReservation(UUID eventId) throws StorageException {
        update("DELETE FROM outbox WHERE event_id = ? AND ready = 0", eventId);
    }

    public synchronized void addReady(OutboxEvent e) throws StorageException {
        insert(e, 1);
    }

    /**
     * Após uma queda no meio de uma operação, reservas não finalizadas viram OFFLINE_ENTRY_UNCERTAIN: não se sabe se a
     * porta abriu, o uso permanece consumido e a central sinaliza para revisão humana.
     */
    public synchronized int recoverReservations() throws StorageException {
        try (Statement s = db.createStatement()) {
            return s.executeUpdate("UPDATE outbox SET ready = 1, type = 'OFFLINE_ENTRY_UNCERTAIN', "
                    + "payload = json_set(payload, '$.type', 'OFFLINE_ENTRY_UNCERTAIN') WHERE ready = 0");
        } catch (SQLException e) {
            throw new StorageException("recoverReservations", e);
        }
    }

    public synchronized List<OutboxEvent> readyBatch(int limit) throws StorageException {
        List<OutboxEvent> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement(
                "SELECT payload FROM outbox WHERE ready = 1 ORDER BY created_at, rowid LIMIT ?")) {
            p.setInt(1, limit);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    out.add(fromJson(rs.getString(1)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new StorageException("readyBatch", e);
        }
    }

    public synchronized void deleteEvents(Collection<UUID> ids) throws StorageException {
        for (UUID id : ids) {
            update("DELETE FROM outbox WHERE event_id = ?", id);
        }
    }

    public synchronized int backlog() throws StorageException {
        try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM outbox")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new StorageException("backlog", e);
        }
    }

    // ---- comandos e metadados ----

    /** @return true se o comando é novo; false se já foi visto (nunca é executado duas vezes) */
    public synchronized boolean markCommandSeen(UUID commandId) throws StorageException {
        try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO seen_commands(command_id, at) VALUES (?, ?)")) {
            p.setString(1, commandId.toString());
            p.setString(2, Instant.now().toString());
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new StorageException("markCommandSeen", e);
        }
    }

    public synchronized Optional<String> meta(String key) throws StorageException {
        try (PreparedStatement p = db.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            p.setString(1, key);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("meta", e);
        }
    }

    public synchronized void putMeta(String key, String value) throws StorageException {
        try (PreparedStatement p = db.prepareStatement(
                "INSERT INTO meta(k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            p.setString(1, key);
            p.setString(2, value);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("putMeta", e);
        }
    }

    /** Sonda de escrita: o armazenamento aceita gravação agora? */
    public synchronized boolean isWritable() {
        try {
            // Escrita de tamanho relevante (não um UPDATE trivial): detecta falta de espaço de verdade.
            putMeta("probe", Instant.now() + "|" + "x".repeat(1024));
            putMeta("probe", Instant.now().toString());
            return true;
        } catch (StorageException e) {
            return false;
        }
    }

    /** Somente testes: enche o arquivo até o limite de páginas, simulando disco cheio (SQLITE_FULL). */
    synchronized void exhaustStorageForTesting() throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS filler(b BLOB)");
            long pages;
            try (ResultSet rs = s.executeQuery("PRAGMA page_count")) {
                rs.next();
                pages = rs.getLong(1);
            }
            s.execute("PRAGMA max_page_count = " + (pages + 4));
            try {
                for (int i = 0; i < 100_000; i++) {
                    s.execute("INSERT INTO filler VALUES (zeroblob(2048))");
                }
            } catch (SQLException expected) {
                return; // cheio
            }
            throw new SQLException("storage did not fill up");
        }
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException e) {
            log.warn("Error closing local store", e);
        }
    }

    private void insert(OutboxEvent e, int ready) throws StorageException {
        try (PreparedStatement p = db.prepareStatement(
                "INSERT INTO outbox(event_id, type, credential_id, payload, created_at, ready) VALUES (?, ?, ?, ?, ?, ?)")) {
            p.setString(1, e.eventId().toString());
            p.setString(2, e.type());
            p.setString(3, e.credentialId() == null ? null : e.credentialId().toString());
            p.setString(4, Json.MAPPER.writeValueAsString(e.toMap()));
            p.setString(5, e.occurredAt().toString());
            p.setInt(6, ready);
            p.executeUpdate();
        } catch (SQLException ex) {
            throw new StorageException("insert outbox", ex);
        }
    }

    private void update(String sql, UUID id) throws StorageException {
        try (PreparedStatement p = db.prepareStatement(sql)) {
            p.setString(1, id.toString());
            p.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException(sql, e);
        }
    }

    private static OutboxEvent fromJson(String json) {
        JsonNode n = Json.MAPPER.readTree(json);
        return new OutboxEvent(UUID.fromString(n.get("eventId").asString()), n.get("type").asString(),
                Instant.parse(n.get("occurredAt").asString()), uuid(n, "credentialId"), uuid(n, "deviceId"),
                uuid(n, "commandId"), text(n, "entrance"), text(n, "reason"));
    }

    private static UUID uuid(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : UUID.fromString(v);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
