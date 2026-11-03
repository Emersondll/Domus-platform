package br.com.domus.platform.shared;

import java.util.Collection;
import java.util.UUID;

/** Contrato para levar bens da unidade (veículos) junto numa mudança de morador; quem os guarda implementa. */
public interface UnitAssetMover {

    /**
     * Move veículos ativos de uma unidade para outra do mesmo condomínio. Todos os ids precisam ser da unidade de origem; se um
     * não for, nada é movido e a operação falha (404).
     *
     * @return quantos veículos foram movidos
     */
    int moveVehicles(UUID actorUserId, UUID condominiumId, UUID fromUnitId, UUID toUnitId, Collection<UUID> vehicleIds);
}
