package br.com.domus.platform.shared;

import java.util.UUID;

/** Contrato para encerrar o acesso de um morador quando o cadastro dele é desativado (sem acoplar os módulos). */
public interface ResidentAccessRevoker {

    /**
     * Revoga o vínculo de acesso do morador e as sessões de vídeo que ele já tinha aberto.
     *
     * @param actorUserId quem desativou o cadastro, para a auditoria
     * @param condominiumId condomínio
     * @param residentId cadastro do morador desativado
     * @return quantidade de vínculos revogados (zero se o morador nunca recebeu conta)
     */
    int revokeResidentAccess(UUID actorUserId, UUID condominiumId, UUID residentId);
}
