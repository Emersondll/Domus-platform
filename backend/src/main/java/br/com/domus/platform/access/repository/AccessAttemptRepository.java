package br.com.domus.platform.access.repository;

import br.com.domus.platform.access.entity.AccessAttempt;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso ao histórico de tentativas. */
public interface AccessAttemptRepository extends JpaRepository<AccessAttempt, UUID> {

    Page<AccessAttempt> findByCondominiumId(UUID condominiumId, Pageable pageable);

    /** Tentativas feitas com credenciais da unidade informada. */
    @Query(value = """
            SELECT a FROM AccessAttempt a
            WHERE a.condominiumId = :condominiumId
              AND (a.credentialId IN (SELECT c.id FROM AccessCredential c
                                      WHERE c.condominiumId = :condominiumId AND c.unitId = :unitId)
                   OR a.visitRequestId IN (SELECT v.id FROM VisitRequest v
                                           WHERE v.condominiumId = :condominiumId AND v.unitId = :unitId))
            """, countQuery = """
            SELECT count(a) FROM AccessAttempt a
            WHERE a.condominiumId = :condominiumId
              AND (a.credentialId IN (SELECT c.id FROM AccessCredential c
                                      WHERE c.condominiumId = :condominiumId AND c.unitId = :unitId)
                   OR a.visitRequestId IN (SELECT v.id FROM VisitRequest v
                                           WHERE v.condominiumId = :condominiumId AND v.unitId = :unitId))
            """)
    Page<AccessAttempt> findByUnit(@Param("condominiumId") UUID condominiumId, @Param("unitId") UUID unitId,
                                   Pageable pageable);

    boolean existsByVisitRequestId(UUID visitRequestId);

    /** Quais pedidos (entre os informados) já tiveram a entrada registrada. */
    @Query("SELECT a.visitRequestId FROM AccessAttempt a WHERE a.condominiumId = :condominiumId AND a.visitRequestId IN :ids")
    java.util.List<UUID> findEnteredRequestIds(@Param("condominiumId") UUID condominiumId,
                                               @Param("ids") java.util.Collection<UUID> ids);

    Optional<AccessAttempt> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha até o fim da transação (a saída é registrada uma única vez). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccessAttempt> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Busca pelo número do ticket de saída, com trava (o ticket é validado uma única vez). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccessAttempt> findWithLockByCondominiumIdAndExitTicket(UUID condominiumId, Integer exitTicket);

    /** Entradas concedidas ainda sem saída registrada (quem está no local). */
    @Query("""
            SELECT a FROM AccessAttempt a
            WHERE a.condominiumId = :condominiumId AND a.result = 'GRANTED' AND a.exitedAt IS NULL
            """)
    Page<AccessAttempt> findOnSite(@Param("condominiumId") UUID condominiumId, Pageable pageable);

    /** Tentativas do período, com filtro opcional de resultado (GRANTED/DENIED). */
    @Query("""
            SELECT a FROM AccessAttempt a
            WHERE a.condominiumId = :condominiumId AND a.occurredAt >= :from AND a.occurredAt < :to
              AND (:result IS NULL OR a.result = :result)
            """)
    Page<AccessAttempt> findInPeriod(@Param("condominiumId") UUID condominiumId, @Param("from") Instant from,
                                     @Param("to") Instant to, @Param("result") String result, Pageable pageable);
}
