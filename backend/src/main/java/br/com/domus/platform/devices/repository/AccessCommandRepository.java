package br.com.domus.platform.devices.repository;

import br.com.domus.platform.devices.entity.AccessCommand;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comandos físicos. As transições de estado são UPDATEs condicionais: uma confirmação nunca é sobrescrita por
 * um "enviado" que chegue atrasado, e nada volta de CONFIRMED.
 */
public interface AccessCommandRepository extends JpaRepository<AccessCommand, UUID> {

    Optional<AccessCommand> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    Optional<AccessCommand> findByCondominiumIdAndIdempotencyKey(UUID condominiumId, String idempotencyKey);

    long countByRequestedByAndRequestedAtAfter(UUID requestedBy, Instant after);

    Page<AccessCommand> findByCondominiumId(UUID condominiumId, Pageable pageable);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE access_command SET state = 'SENT', sent_at = :now WHERE id = :id AND state = 'PENDING'",
            nativeQuery = true)
    int markSent(@Param("id") UUID id, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE access_command SET state = 'CONFIRMED', evidence = :evidence, resolved_at = :now
            WHERE id = :id AND device_id = :deviceId AND state IN ('PENDING', 'SENT', 'UNKNOWN')
            """, nativeQuery = true)
    int markConfirmed(@Param("id") UUID id, @Param("deviceId") UUID deviceId, @Param("evidence") String evidence,
                      @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE access_command SET state = 'FAILED', failure_reason = :reason, resolved_at = :now
            WHERE id = :id AND state IN ('PENDING', 'SENT')
            """, nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE access_command SET state = 'UNKNOWN', failure_reason = :reason, resolved_at = :now
            WHERE id = :id AND state IN ('PENDING', 'SENT')
            """, nativeQuery = true)
    int markUnknown(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    /** Comandos sem desfecho após o prazo viram UNKNOWN (sem presumir abertura nem falha). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE access_command SET state = 'UNKNOWN', failure_reason = 'NO_CONFIRMATION', resolved_at = :now
            WHERE state IN ('PENDING', 'SENT') AND requested_at < :cutoff
            """, nativeQuery = true)
    int expireUnconfirmed(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
