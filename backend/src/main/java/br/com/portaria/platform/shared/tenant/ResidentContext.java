package br.com.portaria.platform.shared.tenant;

import java.util.UUID;

/** Morador autenticado em um condomínio: o ator e o cadastro de morador ao qual está vinculado. */
public record ResidentContext(Actor actor, UUID residentId, UUID condominiumId) {
}
