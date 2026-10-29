package br.com.domus.platform.commercial.service;

import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.shared.tenant.CondominiumLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implementa o contrato de tenancy sobre o repositório de condomínios, sem depender de {@link CommercialService}. */
@Component
class CondominiumLookupAdapter implements CondominiumLookup {

    private final CondominiumRepository condominiums;
    private final OperatorCompanyRepository companies;

    CondominiumLookupAdapter(CondominiumRepository condominiums, OperatorCompanyRepository companies) {
        this.condominiums = condominiums;
        this.companies = companies;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> companyIdOf(UUID condominiumId) {
        return condominiums.findById(condominiumId).map(Condominium::getOperatorCompanyId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSuspended(UUID condominiumId) {
        return condominiums.isSuspendedOrCompanySuspended(condominiumId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isCompanySuspended(UUID companyId) {
        return companies.existsByIdAndStatus(companyId, "SUSPENDED");
    }
}
