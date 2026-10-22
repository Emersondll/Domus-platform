package br.com.domus.platform.commercial;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a condomínios. */
public interface CondominiumRepository extends JpaRepository<Condominium, UUID> {

    @Query("select c from Condominium c where c.id in :condominiumIds or c.operatorCompanyId in :companyIds")
    Page<Condominium> findVisible(@Param("condominiumIds") Collection<UUID> condominiumIds,
                                  @Param("companyIds") Collection<UUID> companyIds, Pageable pageable);

    boolean existsByOperatorCompanyIdAndName(UUID operatorCompanyId, String name);
}
