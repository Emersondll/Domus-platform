package br.com.portaria.platform.devices.adapter;

import java.util.UUID;

/** Destino interno de eventos gerados por adaptadores (o simulador emite eventos como um equipamento real). */
public interface DeviceEventSink {

    void onTrustedEvent(UUID deviceId, UUID condominiumId, String type, UUID commandId);
}
