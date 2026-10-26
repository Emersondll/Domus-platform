package br.com.domus.platform.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato pelo qual os dispositivos informam a passagem de uma pessoa por um ponto de acesso. Quem implementa decide se
 * a referência pertence a um morador cadastrado; referência desconhecida não gera registro pessoal.
 */
public interface ResidentPassageRecorder {

    /**
     * @param deviceId          dispositivo que registrou a passagem
     * @param condominiumId     condomínio do dispositivo
     * @param entrance          entrada (ponto de acesso) do dispositivo
     * @param configuredDirection sentido configurado no dispositivo (IN, OUT) ou {@code null}; prevalece sobre o informado
     * @param reportedDirection   sentido informado pelo evento ou {@code null}
     * @param reportedMethod      meio informado pelo evento ou {@code null}
     * @param identityRef         referência da pessoa no equipamento (cartão, usuário...), nunca {@code null} aqui
     * @param granted             se o acesso foi concedido
     * @param occurredAt          instante da passagem
     */
    record Passage(UUID deviceId, UUID condominiumId, String entrance, String configuredDirection,
                   String reportedDirection, String reportedMethod, String identityRef, boolean granted,
                   Instant occurredAt) {
    }

    void record(Passage passage);
}
