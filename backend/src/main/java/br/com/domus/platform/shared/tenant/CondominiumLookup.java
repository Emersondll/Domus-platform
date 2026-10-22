package br.com.domus.platform.shared.tenant;

import java.util.Optional;
import java.util.UUID;

/** Contrato para o módulo de tenancy descobrir a empresa dona de um condomínio, sem depender do módulo comercial. */
public interface CondominiumLookup {

    Optional<UUID> companyIdOf(UUID condominiumId);
}
