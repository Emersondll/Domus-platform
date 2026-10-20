package br.com.portaria.platform.shared;

import java.util.UUID;

/** Contrato para o módulo de operações exibir a saúde dos dispositivos sem depender do módulo de dispositivos. */
public interface DeviceHealthLookup {

    /** @return NO_DEVICES, ONLINE n/n, DEGRADED x/n ou UNKNOWN n/n */
    String healthSummary(UUID condominiumId);
}
