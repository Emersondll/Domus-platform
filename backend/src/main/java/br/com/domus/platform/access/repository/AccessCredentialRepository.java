package br.com.domus.platform.access.repository;

import br.com.domus.platform.access.entity.AccessCredential;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Acesso a credenciais, sempre escopado por condomínio. */
public interface AccessCredentialRepository extends JpaRepository<AccessCredential, UUID> {

    List<AccessCredential> findByCondominiumIdAndCreatedByUserId(UUID condominiumId, UUID userId);

    Optional<AccessCredential> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    Page<AccessCredential> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    /**
     * Consome um uso de forma atômica: validade, quota e revogação são verificadas na própria instrução
     * UPDATE, de modo que duas validações simultâneas de uma credencial de uso único nunca somam dois usos.
     *
     * @return 1 se o uso foi consumido; 0 se a credencial não está apta
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE access_credential SET use_count = use_count + 1
            WHERE id = :id AND condominium_id = :condominiumId AND status = 'ACTIVE'
              AND use_count < max_uses AND valid_from <= :now AND valid_until > :now
            """, nativeQuery = true)
    int consumeUse(@Param("id") UUID id, @Param("condominiumId") UUID condominiumId, @Param("now") Instant now);

    /** Credenciais que o agente pode aceitar offline: ativas, não vencidas e com quota restante. */
    @Query("select c from AccessCredential c where c.condominiumId = :condominiumId and c.status = 'ACTIVE' "
            + "and c.validUntil > :now and c.useCount < c.maxUses order by c.validUntil")
    List<AccessCredential> findOfflineEligible(@Param("condominiumId") UUID condominiumId, @Param("now") Instant now,
                                               Pageable pageable);

    /** Aplica um uso feito offline sem ultrapassar a quota; 0 linhas = quota já esgotada na central. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE access_credential SET use_count = use_count + 1
            WHERE id = :id AND condominium_id = :condominiumId AND use_count < max_uses
            """, nativeQuery = true)
    int applyOfflineUse(@Param("id") UUID id, @Param("condominiumId") UUID condominiumId);
}
