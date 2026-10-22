package br.com.domus.platform.commercial;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a empresas operadoras. */
public interface OperatorCompanyRepository extends JpaRepository<OperatorCompany, UUID> {

    boolean existsByTaxId(String taxId);
}
