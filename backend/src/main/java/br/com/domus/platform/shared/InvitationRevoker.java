package br.com.domus.platform.shared;

import java.util.UUID;

/** Contrato para revogar convites ativos criados por um usuário numa unidade (sem acoplar identidade e acesso). */
public interface InvitationRevoker {

    /**
     * Revoga os convites ainda ativos que o usuário criou para a unidade. Convites já revogados ou vencidos não mudam.
     *
     * @param actorUserId quem motivou a revogação, para a auditoria
     * @param condominiumId condomínio
     * @param unitId unidade dos convites
     * @param createdByUserId usuário que criou os convites
     * @return quantidade de convites revogados
     */
    int revokeActiveInvitations(UUID actorUserId, UUID condominiumId, UUID unitId, UUID createdByUserId);
}
