package br.com.domus.platform.operations.repository;

import br.com.domus.platform.operations.entity.ServiceCall;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a atendimentos. */
public interface ServiceCallRepository extends JpaRepository<ServiceCall, UUID> {

    Optional<ServiceCall> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    Page<ServiceCall> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<ServiceCall> findFirstByCondominiumIdAndUnitIdAndSourceAndStatusNot(UUID condominiumId, UUID unitId,
                                                                                String source, String status);

    Page<ServiceCall> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    Page<ServiceCall> findByAssignedUserIdAndStatus(UUID userId, String status, Pageable pageable);

    /**
     * Assume atomicamente a chamada mais antiga da fila (alertas de pânico passam na frente) entre os condomínios informados. Com
     * {@code FOR UPDATE SKIP LOCKED}, dois operadores simultâneos nunca recebem a mesma chamada.
     *
     * @return o ID da chamada assumida, se houver
     */
    @Query(value = """
            UPDATE service_call SET status = 'ASSIGNED', assigned_user_id = :userId, assigned_at = :now
            WHERE id = (SELECT id FROM service_call
                        WHERE status = 'QUEUED' AND condominium_id IN (:condominiumIds)
                        ORDER BY (source = 'PANIC') DESC, created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> claimNext(@Param("userId") UUID userId, @Param("condominiumIds") Collection<UUID> condominiumIds,
                             @Param("now") Instant now);

    @Query(value = "SELECT count(*) FROM service_call WHERE condominium_id = :c AND status = :status",
            nativeQuery = true)
    long countByStatus(@Param("c") UUID condominiumId, @Param("status") String status);

    @Query(value = "SELECT count(*) FROM service_call WHERE condominium_id = :c AND status = 'QUEUED' AND source = 'PANIC'",
            nativeQuery = true)
    long countQueuedPanics(@Param("c") UUID condominiumId);
}
