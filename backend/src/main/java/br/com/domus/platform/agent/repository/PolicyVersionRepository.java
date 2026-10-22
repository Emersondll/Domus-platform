package br.com.domus.platform.agent.repository;

import br.com.domus.platform.agent.entity.PolicyVersionRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Contador de versão da política por condomínio (UPSERT atômico). */
public interface PolicyVersionRepository extends JpaRepository<PolicyVersionRow, UUID> {

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO condominium_policy_version (condominium_id, version) VALUES (:c, 2)
            ON CONFLICT (condominium_id) DO UPDATE SET version = condominium_policy_version.version + 1
            """, nativeQuery = true)
    int bump(@Param("c") UUID condominiumId);

    Optional<PolicyVersionRow> findByCondominiumId(UUID condominiumId);
}
