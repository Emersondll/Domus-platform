package br.com.domus.platform.devices.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Credenciais de câmera já cifradas. Esta classe só move texto cifrado; quem cifra e decifra é o serviço. */
@Repository
public class DeviceCredentialStore {

    /**
     * Credencial guardada.
     *
     * @param usernameEnc usuário cifrado
     * @param passwordEnc senha cifrada
     * @param updatedAt   última troca
     */
    public record Stored(String usernameEnc, String passwordEnc, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    /** Construtor de DeviceCredentialStore; recebe as dependências por injeção. */
    public DeviceCredentialStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava ou substitui a credencial do dispositivo (rotação). */
    public void upsert(UUID deviceId, UUID condominiumId, String usernameEnc, String passwordEnc, UUID updatedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO device_credential (device_id, condominium_id, username_enc, password_enc, updated_by, updated_at)
                VALUES (:d, :c, :u, :p, :by, :at)
                ON CONFLICT (device_id) DO UPDATE SET username_enc = EXCLUDED.username_enc,
                    password_enc = EXCLUDED.password_enc, updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """).param("d", deviceId).param("c", condominiumId).param("u", usernameEnc).param("p", passwordEnc)
                .param("by", updatedBy).param("at", Timestamp.from(now)).update();
    }

    /** Lê a credencial cifrada do dispositivo, se houver. */
    public Optional<Stored> find(UUID deviceId) {
        return jdbc.sql("SELECT username_enc, password_enc, updated_at FROM device_credential WHERE device_id = :d")
                .param("d", deviceId)
                .query((rs, n) -> new Stored(rs.getString("username_enc"), rs.getString("password_enc"),
                        rs.getTimestamp("updated_at").toInstant())).optional();
    }

    /** Remove a credencial; devolve se havia alguma. */
    public boolean delete(UUID deviceId) {
        return jdbc.sql("DELETE FROM device_credential WHERE device_id = :d").param("d", deviceId).update() > 0;
    }
}
