package br.com.domus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Criptografia em repouso do armazenamento: nada sensível no arquivo, chave errada não apaga dados, adulteração é detectada. */
class StoreEncryptionTest {

    @TempDir Path dir;

    private static final String POLICY = "{\"credentials\":[{\"id\":\"c1\",\"sig\":\"SEGREDO-DA-ASSINATURA-DO-QR\"}]}";

    private String raw(Path file, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void execute(Path file, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void policyEventsAndApiKeyAreNotReadableInTheFileButRoundTrip() throws Exception {
        Path file = dir.resolve("agent.db");
        UUID eventId = UUID.randomUUID();
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            store.savePolicy(POLICY, "assinatura");
            store.putMeta("apiKey", "CHAVE-DE-API-DO-AGENTE");
            store.addReady(new OutboxEvent(eventId, "OFFLINE_ENTRY_GRANTED", Instant.now(), UUID.randomUUID(), null, null, "portaria", null));
            assertThat(store.loadPolicy().orElseThrow().json()).isEqualTo(POLICY);
            assertThat(store.meta("apiKey")).contains("CHAVE-DE-API-DO-AGENTE");
            assertThat(store.readyBatch(10)).extracting(OutboxEvent::eventId).containsExactly(eventId);
        }
        assertThat(raw(file, "SELECT json FROM policy")).startsWith("enc:v1:").doesNotContain("SEGREDO");
        assertThat(raw(file, "SELECT v FROM meta WHERE k = 'apiKey'")).startsWith("enc:v1:").doesNotContain("CHAVE-DE-API");
        assertThat(raw(file, "SELECT payload FROM outbox")).startsWith("enc:v1:").doesNotContain("OFFLINE_ENTRY_GRANTED");
    }

    @Test
    void aWrongKeyRefusesToOpenAndLeavesTheFileUntouched() throws Exception {
        Path file = dir.resolve("agent.db");
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            store.savePolicy(POLICY, "assinatura");
        }
        byte[] before = Files.readAllBytes(file);
        StoreCipher other = StoreCipher.fromSecret("a-completely-different-store-key-0123456789");
        assertThatThrownBy(() -> LocalStore.open(file, other)).isInstanceOf(LocalStore.StorageException.class)
                .hasMessageContaining("does not match");
        // não foi tratado como corrupção: nada de arquivo ".corrupt-" nem recriação
        assertThat(Files.list(dir).anyMatch(p -> p.getFileName().toString().contains(".corrupt-"))).isFalse();
        try (LocalStore again = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            assertThat(again.loadPolicy().orElseThrow().json()).isEqualTo(POLICY);
            assertThat(again.recoveredFromCorruption()).isFalse();
        }
        assertThat(Files.size(file)).isGreaterThanOrEqualTo(before.length / 2); // continua o mesmo banco
    }

    @Test
    void legacyPlaintextStoreIsEncryptedOnOpen() throws Exception {
        Path file = dir.resolve("agent.db");
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            store.savePolicy("x", "s");
        }
        // simula um armazenamento antigo: valores em texto puro
        UUID id = UUID.randomUUID();
        execute(file, "UPDATE policy SET json = '" + POLICY.replace("'", "''") + "'");
        execute(file, "INSERT INTO meta(k, v) VALUES ('apiKey', 'CHAVE-ANTIGA') ON CONFLICT(k) DO UPDATE SET v = 'CHAVE-ANTIGA'");
        execute(file, "INSERT INTO outbox(event_id, type, credential_id, payload, created_at, ready) VALUES ('" + id
                + "', 'OFFLINE_ENTRY_GRANTED', NULL, '{\"eventId\":\"" + id + "\",\"type\":\"OFFLINE_ENTRY_GRANTED\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\"}', '2026-01-01T00:00:00Z', 1)");
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            assertThat(store.loadPolicy().orElseThrow().json()).isEqualTo(POLICY);
            assertThat(store.meta("apiKey")).contains("CHAVE-ANTIGA");
            assertThat(store.readyBatch(10)).extracting(OutboxEvent::eventId).containsExactly(id);
        }
        assertThat(raw(file, "SELECT json FROM policy")).startsWith("enc:v1:");
        assertThat(raw(file, "SELECT v FROM meta WHERE k = 'apiKey'")).startsWith("enc:v1:");
        assertThat(raw(file, "SELECT payload FROM outbox")).startsWith("enc:v1:");
    }

    @Test
    void swappingEncryptedPayloadsBetweenRowsIsDetected() throws Exception {
        Path file = dir.resolve("agent.db");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            store.addReady(new OutboxEvent(a, "OFFLINE_ENTRY_DENIED", Instant.now(), null, null, null, "portaria", "A"));
            store.addReady(new OutboxEvent(b, "OFFLINE_ENTRY_DENIED", Instant.now(), null, null, null, "portaria", "B"));
        }
        String payloadA = raw(file, "SELECT payload FROM outbox WHERE event_id = '" + a + "'");
        execute(file, "UPDATE outbox SET payload = '" + payloadA + "' WHERE event_id = '" + b + "'");
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            assertThatThrownBy(() -> store.readyBatch(10)).isInstanceOf(LocalStore.StorageException.class);
        }
    }

    @Test
    void interruptedReservationsAreRecoveredAsUncertainEvenThoughPayloadsAreEncrypted() throws Exception {
        Path file = dir.resolve("agent.db");
        UUID id = UUID.randomUUID();
        try (LocalStore store = LocalStore.open(file, TestKit.STORE_CIPHER)) {
            store.reserve(new OutboxEvent(id, "OFFLINE_ENTRY_GRANTED", Instant.now(), UUID.randomUUID(), null, null, "portaria", null));
            assertThat(store.recoverReservations()).isEqualTo(1);
            assertThat(store.readyBatch(10)).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo("OFFLINE_ENTRY_UNCERTAIN");
                assertThat(e.eventId()).isEqualTo(id);
            });
        }
        assertThat(raw(file, "SELECT type FROM outbox")).isEqualTo("OFFLINE_ENTRY_UNCERTAIN");
    }

    @Test
    void theStoreKeyMustBeLongEnough() {
        assertThatThrownBy(() -> StoreCipher.fromSecret("curta")).isInstanceOf(IllegalArgumentException.class);
    }
}
