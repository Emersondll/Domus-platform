package br.com.domus.platform.devices.adapter;

/** Servidor de mídia que converte o fluxo RTSP da câmera em HLS para o navegador. */
public interface MediaServer {

    /** Garante que o caminho {@code path} exista e puxe o vídeo de {@code sourceUrl} sob demanda. */
    void ensurePath(String path, String sourceUrl) throws DeviceAdapter.DeviceCommunicationException;

    /**
     * Informa se há alguém publicando em {@code path} agora (o agente local enviando o vídeo da câmera).
     *
     * @return verdadeiro se o caminho existe e tem fonte pronta; falso se não existe ou não está pronto
     * @throws DeviceAdapter.DeviceCommunicationException se o servidor de mídia não respondeu (estado desconhecido)
     */
    boolean isPublishing(String path) throws DeviceAdapter.DeviceCommunicationException;
}
