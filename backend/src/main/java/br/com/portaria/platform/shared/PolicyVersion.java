package br.com.portaria.platform.shared;

import java.util.UUID;

/** Contrato para sinalizar que a política de contingência de um condomínio mudou (credencial criada/revogada). */
public interface PolicyVersion {

    void bump(UUID condominiumId);
}
