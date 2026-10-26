package br.com.domus.platform.devices.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente da API de controle do MediaMTX (escuta só em loopback, fora da autenticação por HTTP do servidor).
 * Registra o caminho da câmera com {@code sourceOnDemand}: o servidor só abre o RTSP enquanto houver espectador.
 */
@Component
class MediaMtxClient implements MediaServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    MediaMtxClient(@Value("${portaria.media.api-url:http://127.0.0.1:9997}") String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Override
    public void ensurePath(String path, String sourceUrl) throws DeviceAdapter.DeviceCommunicationException {
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "source", sourceUrl, "sourceOnDemand", true, "rtspTransport", "tcp"));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + "/v3/config/paths/replace/" + path))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new DeviceAdapter.DeviceCommunicationException("Media server rejected the path");
            }
        } catch (IOException e) {
            throw new DeviceAdapter.DeviceCommunicationException("Media server unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceAdapter.DeviceCommunicationException("Media server call interrupted");
        }
    }
}
