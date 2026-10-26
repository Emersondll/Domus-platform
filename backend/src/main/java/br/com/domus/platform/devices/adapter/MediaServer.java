package br.com.domus.platform.devices.adapter;

/** Servidor de mídia que converte o fluxo RTSP da câmera em HLS para o navegador. */
public interface MediaServer {

    /** Garante que o caminho {@code path} exista e puxe o vídeo de {@code sourceUrl} sob demanda. */
    void ensurePath(String path, String sourceUrl) throws DeviceAdapter.DeviceCommunicationException;
}
