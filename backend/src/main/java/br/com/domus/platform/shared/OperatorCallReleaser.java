package br.com.domus.platform.shared;

import java.util.UUID;

/** Contrato para devolver à fila as chamadas de um operador cujo vínculo acabou (sem acoplar identidade e operações). */
public interface OperatorCallReleaser {

    /**
     * Devolve à fila as chamadas em atendimento do usuário no condomínio.
     *
     * @param actorUserId quem revogou o vínculo (para a trilha da chamada)
     * @param userId operador que perdeu o vínculo
     * @param condominiumId condomínio
     * @return quantidade de chamadas devolvidas à fila
     */
    int releaseCallsOf(UUID actorUserId, UUID userId, UUID condominiumId);
}
