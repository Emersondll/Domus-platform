package br.com.domus.platform.devices.adapter;

import java.util.UUID;

/** Destino interno de eventos gerados por adaptadores (o simulador emite eventos como um equipamento real). */
public interface DeviceEventSink {

    /**
     * Quem passou, quando o equipamento informa.
     *
     * @param identityRef referência da pessoa no equipamento (cartão, usuário do terminal), ou {@code null}
     * @param method      CARD, QR, TERMINAL_USER, APP ou FACE, ou {@code null}
     * @param direction   IN ou OUT, ou {@code null}; o sentido configurado no dispositivo prevalece
     */
    record Person(String identityRef, String method, String direction) {
    }

    void onTrustedEvent(UUID deviceId, UUID condominiumId, String type, UUID commandId, Person person);

    default void onTrustedEvent(UUID deviceId, UUID condominiumId, String type, UUID commandId) {
        onTrustedEvent(deviceId, condominiumId, type, commandId, null);
    }
}
