package br.com.domus.platform.shared.tenant;

import java.util.Optional;
import java.util.UUID;

/** Contrato para o módulo de tenancy descobrir a empresa dona de um condomínio, sem depender do módulo comercial. */
public interface CondominiumLookup {

    Optional<UUID> companyIdOf(UUID condominiumId);

    /** Verdadeiro se o condomínio ou a empresa dona dele está suspensa (acesso ao app desligado, registros mantidos). */
    boolean isSuspended(UUID condominiumId);

    /** Verdadeiro se a empresa operadora está suspensa. */
    boolean isCompanySuspended(UUID companyId);
}
