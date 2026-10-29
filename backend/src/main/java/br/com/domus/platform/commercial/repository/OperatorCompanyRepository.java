package br.com.domus.platform.commercial.repository;

import br.com.domus.platform.commercial.entity.OperatorCompany;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a empresas operadoras. */
public interface OperatorCompanyRepository extends JpaRepository<OperatorCompany, UUID> {

    boolean existsByTaxId(String taxId);

    boolean existsByIdAndStatus(UUID id, String status);
}
