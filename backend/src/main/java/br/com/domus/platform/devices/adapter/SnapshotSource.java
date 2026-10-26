package br.com.domus.platform.devices.adapter;

import br.com.domus.platform.devices.entity.Device;

/** Origem de um quadro de câmera (um JPEG ou PNG qualquer; o serviço normaliza depois). */
public interface SnapshotSource {

    boolean supports(Device camera);

    byte[] capture(Device camera) throws DeviceAdapter.DeviceCommunicationException;
}
