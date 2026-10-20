package br.com.portaria.platform.shared;

import java.util.UUID;

/** Contrato para o painel exibir a conectividade do condomínio sem depender do módulo do agente. */
public interface ConnectivityLookup {

    /** @return NO_AGENT, ONLINE, OFFLINE (since ...) com a quantidade de eventos pendentes de sincronização */
    String connectivitySummary(UUID condominiumId);
}
