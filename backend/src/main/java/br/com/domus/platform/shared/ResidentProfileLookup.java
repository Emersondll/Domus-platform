package br.com.domus.platform.shared;

import java.util.UUID;

/** Contrato para consultar o perfil do morador sem depender do módulo de moradores. */
public interface ResidentProfileLookup {

    /**
     * Informa se o morador é adulto (qualquer tipo menos dependente). Morador inexistente no condomínio conta como não adulto.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @return verdadeiro se for adulto
     */
    boolean isAdult(UUID condominiumId, UUID residentId);
}
