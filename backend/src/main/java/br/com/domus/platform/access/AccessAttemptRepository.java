package br.com.domus.platform.access;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso ao histórico de tentativas. */
public interface AccessAttemptRepository extends JpaRepository<AccessAttempt, UUID> {

    Page<AccessAttempt> findByCondominiumId(UUID condominiumId, Pageable pageable);

    /** Tentativas feitas com credenciais da unidade informada. */
    @Query(value = """
            SELECT a FROM AccessAttempt a
            WHERE a.condominiumId = :condominiumId
              AND a.credentialId IN (SELECT c.id FROM AccessCredential c
                                     WHERE c.condominiumId = :condominiumId AND c.unitId = :unitId)
            """, countQuery = """
            SELECT count(a) FROM AccessAttempt a
            WHERE a.condominiumId = :condominiumId
              AND a.credentialId IN (SELECT c.id FROM AccessCredential c
                                     WHERE c.condominiumId = :condominiumId AND c.unitId = :unitId)
            """)
    Page<AccessAttempt> findByUnit(@Param("condominiumId") UUID condominiumId, @Param("unitId") UUID unitId,
                                   Pageable pageable);
}
