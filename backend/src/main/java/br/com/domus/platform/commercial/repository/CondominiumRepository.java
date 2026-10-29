package br.com.domus.platform.commercial.repository;

import br.com.domus.platform.commercial.entity.Condominium;
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

    java.util.List<Condominium> findByOperatorCompanyId(UUID operatorCompanyId);

    @Query("select count(c) > 0 from Condominium c, OperatorCompany o where c.id = :id and o.id = c.operatorCompanyId "
            + "and (c.status = 'SUSPENDED' or o.status = 'SUSPENDED')")
    boolean isSuspendedOrCompanySuspended(@Param("id") UUID condominiumId);
}
