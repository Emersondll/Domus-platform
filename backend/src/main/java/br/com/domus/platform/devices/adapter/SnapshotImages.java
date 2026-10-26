package br.com.domus.platform.devices.adapter;

import br.com.domus.platform.shared.image.JpegImages;

/** Normaliza a imagem de uma câmera (limite de tamanho e JPEG); imagem inválida vira falha de comunicação com o equipamento. */
public final class SnapshotImages {

    public static final int MAX_SIDE = JpegImages.MAX_SIDE;
    public static final int MAX_BYTES = JpegImages.MAX_BYTES;

    private SnapshotImages() {
    }

    public static byte[] normalize(byte[] raw) throws DeviceAdapter.DeviceCommunicationException {
        try {
            return JpegImages.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Camera did not return a valid image");
        }
    }
}
