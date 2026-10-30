package br.com.domus.platform.shared;

import java.util.UUID;

/** Contrato para conferir as credenciais de um agente local fora das rotas do agente (ex.: publicação de vídeo). */
public interface AgentCredentialVerifier {

    /**
     * Confere a chave de um agente ativo, de um condomínio não suspenso. Não registra contato do agente nem lança exceção.
     *
     * @param agentId agente
     * @param apiKey chave informada
     * @param condominiumId condomínio ao qual o agente precisa pertencer
     * @return verdadeiro só se o agente é ativo, do condomínio informado, e a chave (atual ou anterior ainda válida) confere
     */
    boolean verify(UUID agentId, String apiKey, UUID condominiumId);
}
