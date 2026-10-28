package br.com.domus.platform.devices.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sessões de visualização de vídeo. A validação feita pelo servidor de mídia a cada requisição e as revogações são
 * instruções curtas, sem entidade JPA nem transação longa (o caminho de autorização é quente).
 */
@Repository
public class CameraViewSessionStore {

    private final JdbcClient jdbc;

    /** Construtor de CameraViewSessionStore; recebe as dependências por injeção. */
    public CameraViewSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava uma sessão nova. */
    public void create(UUID id, UUID condominiumId, UUID deviceId, UUID userId, String kind, Instant now, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO camera_view_session (id, condominium_id, device_id, user_id, kind, created_at, expires_at)
                VALUES (:id, :condo, :device, :user, :kind, :now, :exp)
                """).param("id", id).param("condo", condominiumId).param("device", deviceId).param("user", userId)
                .param("kind", kind).param("now", ts(now)).param("exp", ts(expiresAt)).update();
    }

    /** A sessão existe para a câmera, não foi revogada e não venceu. */
    public boolean isActive(UUID sessionId, UUID deviceId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM camera_view_session
                WHERE id = :id AND device_id = :device AND revoked_at IS NULL AND expires_at > :now
                """).param("id", sessionId).param("device", deviceId).param("now", ts(now)).query(Long.class).single() > 0;
    }

    /**
     * Serializa, até o fim da transação, as aberturas e revogações do mesmo usuário. Sem isso, duas aberturas simultâneas
     * travavam as mesmas linhas em ordens diferentes (deadlock achado pelo teste de carga) e as duas passavam do teto.
     * Usuários diferentes não se bloqueiam. Precisa ser chamado dentro de uma transação.
     */
    public void lockUser(UUID userId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:user, 0))").param("user", userId.toString()).query().singleRow();
    }

    /** Mantém só as {@code keep} sessões ativas mais novas do usuário; revoga as mais antigas. */
    public int enforceLimit(UUID userId, int keep, Instant now) {
        return jdbc.sql("""
                UPDATE camera_view_session SET revoked_at = :now, revoked_reason = 'LIMIT'
                WHERE id IN (SELECT id FROM camera_view_session
                             WHERE id IN (SELECT id FROM camera_view_session
                                          WHERE user_id = :user AND revoked_at IS NULL AND expires_at > :now
                                          ORDER BY created_at DESC, id OFFSET :keep)
                             ORDER BY id FOR UPDATE)
                """).param("user", userId).param("keep", keep).param("now", ts(now)).update();
    }

    /** Revoga as sessões ativas da câmera. Com {@code residentOnly}, só as abertas por moradores. */
    public int revokeByDevice(UUID deviceId, boolean residentOnly, String reason, Instant now) {
        // as linhas são travadas em ordem de id, igual às outras revogações, para duas revogações não se cruzarem
        return jdbc.sql("""
                UPDATE camera_view_session SET revoked_at = :now, revoked_reason = :reason
                WHERE id IN (SELECT id FROM camera_view_session
                             WHERE device_id = :device AND revoked_at IS NULL AND (:residentOnly = FALSE OR kind = 'RESIDENT')
                             ORDER BY id FOR UPDATE)
                """).param("device", deviceId).param("residentOnly", residentOnly).param("reason", reason)
                .param("now", ts(now)).update();
    }

    /** Revoga as sessões ativas de um usuário num condomínio. */
    public int revokeByUser(UUID userId, UUID condominiumId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE camera_view_session SET revoked_at = :now, revoked_reason = :reason
                WHERE id IN (SELECT id FROM camera_view_session
                             WHERE user_id = :user AND condominium_id = :condo AND revoked_at IS NULL
                             ORDER BY id FOR UPDATE)
                """).param("user", userId).param("condo", condominiumId).param("reason", reason)
                .param("now", ts(now)).update();
    }

    /** Apaga as sessões vencidas antes de {@code cutoff}. */
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM camera_view_session WHERE expires_at < :cutoff").param("cutoff", ts(cutoff)).update();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
