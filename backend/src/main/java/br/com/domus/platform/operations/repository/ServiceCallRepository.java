package br.com.domus.platform.operations.repository;

import br.com.domus.platform.operations.entity.ServiceCall;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a atendimentos. */
public interface ServiceCallRepository extends JpaRepository<ServiceCall, UUID> {

    Optional<ServiceCall> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha até o fim da transação (a mudança de estado acontece uma única vez). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ServiceCall> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);

    Page<ServiceCall> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<ServiceCall> findFirstByCondominiumIdAndUnitIdAndSourceAndStatusNot(UUID condominiumId, UUID unitId,
                                                                                String source, String status);

    List<ServiceCall> findByCondominiumIdAndSourceAndStatusOrderByCreatedAt(UUID condominiumId, String source, String status);

    Page<ServiceCall> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    List<ServiceCall> findByAssignedUserIdAndCondominiumIdAndStatus(UUID userId, UUID condominiumId, String status);

    Page<ServiceCall> findByAssignedUserIdAndStatus(UUID userId, String status, Pageable pageable);

    /**
     * Assume atomicamente a chamada mais antiga da fila (alertas de pânico passam na frente) entre os condomínios informados. Com
     * {@code FOR UPDATE SKIP LOCKED}, dois operadores simultâneos nunca recebem a mesma chamada.
     *
     * @return o ID da chamada assumida, se houver
     */
    @Query(value = """
            UPDATE service_call SET status = 'ASSIGNED', assigned_user_id = :userId, assigned_at = :now, last_activity_at = :now
            WHERE id = (SELECT id FROM service_call
                        WHERE status = 'QUEUED' AND condominium_id IN (:condominiumIds)
                        ORDER BY (source = 'PANIC') DESC, created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> claimNext(@Param("userId") UUID userId, @Param("condominiumIds") Collection<UUID> condominiumIds,
                             @Param("now") Instant now);

    /** Chamada devolvida à fila por inatividade: quem a atendia antes e onde ela está. */
    interface InactiveRelease {
        UUID getId();

        UUID getCondominiumId();

        UUID getPreviousUserId();
    }

    /**
     * Devolve à fila, de uma vez só, as chamadas em atendimento sem sinal de vida desde os limites informados (alerta de pânico tem
     * limite próprio, menor). {@code FOR UPDATE SKIP LOCKED}: duas instâncias rodando juntas nunca devolvem a mesma chamada.
     */
    @Query(value = """
            WITH stale AS (
                SELECT id, assigned_user_id AS prev FROM service_call
                WHERE status = 'ASSIGNED'
                  AND last_activity_at < CASE WHEN source = 'PANIC' THEN CAST(:panicCutoff AS timestamptz) ELSE CAST(:cutoff AS timestamptz) END
                FOR UPDATE SKIP LOCKED),
            upd AS (
                UPDATE service_call c SET status = 'QUEUED', assigned_user_id = NULL, assigned_at = NULL, last_activity_at = NULL
                FROM stale s WHERE c.id = s.id
                RETURNING c.id AS id, c.condominium_id AS condo, s.prev AS prev)
            SELECT id AS "id", condo AS "condominiumId", prev AS "previousUserId" FROM upd
            """, nativeQuery = true)
    List<InactiveRelease> releaseInactive(@Param("cutoff") Instant cutoff, @Param("panicCutoff") Instant panicCutoff);

    /** Renova o sinal de vida de tudo o que o operador está atendendo (o painel aberto avisa que ele está ali). */
    @Modifying
    @Query(value = "UPDATE service_call SET last_activity_at = :now WHERE assigned_user_id = :userId AND status = 'ASSIGNED'",
            nativeQuery = true)
    int touchAssigned(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Serializa, até o fim da transação, quem disputa a mesma chave (aqui, as chamadas de uma unidade): sem isso, duas
     * requisições simultâneas passam juntas pela verificação "já existe uma em aberto?".
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l", nativeQuery = true)
    int lockKey(@Param("key") String key);

    @Query(value = "SELECT count(*) FROM service_call WHERE condominium_id = :c AND status = :status",
            nativeQuery = true)
    long countByStatus(@Param("c") UUID condominiumId, @Param("status") String status);

    @Query(value = "SELECT count(*) FROM service_call WHERE condominium_id = :c AND status = 'QUEUED' AND source = 'PANIC'",
            nativeQuery = true)
    long countQueuedPanics(@Param("c") UUID condominiumId);
}
