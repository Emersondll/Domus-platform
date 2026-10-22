package br.com.domus.platform.commercial;

import br.com.domus.platform.shared.tenant.CondominiumLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Implementa o contrato de tenancy sobre o repositório de condomínios, sem depender de {@link CommercialService}. */
@Component
class CondominiumLookupAdapter implements CondominiumLookup {

    private final CondominiumRepository condominiums;

    CondominiumLookupAdapter(CondominiumRepository condominiums) {
        this.condominiums = condominiums;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> companyIdOf(UUID condominiumId) {
        return condominiums.findById(condominiumId).map(Condominium::getOperatorCompanyId);
    }
}
