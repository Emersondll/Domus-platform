package br.com.portaria.platform.devices.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Controladora com API HTTP documentada pelo fabricante. Configuração (não secreta) em {@code device.config}:
 * {@code baseUrl}, {@code openPath}, {@code statusPath}, {@code method} e {@code authTokenEnv} (nome da variável de
 * ambiente com o token). Uma resposta 2xx significa apenas "comando aceito": a confirmação de abertura chega por
 * evento do dispositivo/sensor, nunca pelo código HTTP.
 */
@Component
public class GenericHttpAdapter implements DeviceAdapter {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HostPolicy hosts;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public GenericHttpAdapter(HostPolicy hosts) {
        this.hosts = hosts;
    }

    @Override
    public String type() {
        return "GENERIC_HTTP";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.STATUS, Capability.OPEN_DOOR);
    }

    @Override
    public StatusReport getStatus(DeviceDescriptor device) {
        try {
            URI uri = hosts.requireAllowed(device.config().getOrDefault("baseUrl", ""),
                    device.config().getOrDefault("statusPath", "/status"));
            HttpResponse<Void> response = client.send(builder(uri, device).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() / 100 == 2
                    ? new StatusReport(Health.ONLINE, "HTTP " + response.statusCode())
                    : new StatusReport(Health.OFFLINE, "HTTP " + response.statusCode());
        } catch (DeviceCommunicationException | IOException e) {
            return new StatusReport(Health.OFFLINE, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StatusReport(Health.UNKNOWN, "interrupted");
        }
    }

    @Override
    public CommandReceipt requestAccess(DeviceDescriptor device, AccessRequest request)
            throws DeviceCommunicationException {
        URI uri = hosts.requireAllowed(device.config().getOrDefault("baseUrl", ""),
                device.config().getOrDefault("openPath", "/open"));
        HttpRequest.Builder b = builder(uri, device).header("Idempotency-Key", request.idempotencyKey());
        String method = device.config().getOrDefault("method", "POST");
        HttpRequest http = "GET".equals(method) ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> response = client.send(http, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() / 100 == 2;
            return new CommandReceipt(ok, "HTTP " + response.statusCode());
        } catch (IOException e) {
            throw new DeviceCommunicationException("Device unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceCommunicationException("Interrupted", e);
        }
    }

    private HttpRequest.Builder builder(URI uri, DeviceDescriptor device) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(TIMEOUT);
        String envName = device.config().get("authTokenEnv");
        String token = envName == null ? null : System.getenv(envName);
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }
}
