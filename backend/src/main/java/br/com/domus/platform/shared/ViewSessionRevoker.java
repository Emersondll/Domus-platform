package br.com.domus.platform.shared;

import java.util.UUID;

/** Contrato para encerrar as sessões de vídeo de um usuário quando o vínculo dele muda (sem acoplar os módulos). */
public interface ViewSessionRevoker {

    /**
     * Revoga as sessões de vídeo ativas do usuário no condomínio.
     *
     * @param userId usuário
     * @param condominiumId condomínio
     * @param reason motivo curto, para diagnóstico
     * @return quantidade de sessões revogadas
     */
    int revokeForUser(UUID userId, UUID condominiumId, String reason);

    /** Revoga todas as sessões de vídeo ativas do condomínio (suspensão). */
    int revokeForCondominium(UUID condominiumId, String reason);
}
