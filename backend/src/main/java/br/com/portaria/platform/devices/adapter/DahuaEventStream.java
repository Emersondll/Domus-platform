package br.com.portaria.platform.devices.adapter;

import br.com.portaria.platform.devices.adapter.DeviceAdapter.DeviceCommunicationException;
import br.com.portaria.platform.devices.adapter.DeviceAdapter.DeviceDescriptor;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Assinatura de eventos de controladoras Dahua/Intelbras: {@code GET /cgi-bin/eventManager.cgi?action=attach&codes=[..]
 * &heartbeat=N} com Digest. A resposta nunca termina: é um fluxo multipart com linhas
 * {@code Code=<código>;action=<Start|Stop|Pulse>;index=<n>[;data={json}]}. Linhas que não começam com {@code Code=}
 * (limites do multipart, cabeçalhos, heartbeat) só provam que a conexão está viva.
 *
 * <p>Sem dado recebido por 3 intervalos de heartbeat a conexão é considerada morta e fechada, para o chamador reconectar.
 * <b>Não validado com hardware real</b>; o formato segue a documentação pública e exemplos da comunidade.
 */
public class DahuaEventStream {

    /** Evento do equipamento, sem interpretação. */
    public record Event(String code, String action, String index, String data) {
    }

    /** Receptor do fluxo. */
    public interface Listener {
        /** Qualquer linha recebida: a conexão está viva. */
        void alive();

        void event(Event event);
    }

    static final int HEARTBEAT_SECONDS = 5;

    private final HostPolicy hosts;
    private final DeviceSecrets secrets;
    private final int heartbeatSeconds;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public DahuaEventStream(HostPolicy hosts, DeviceSecrets secrets) {
        this(hosts, secrets, HEARTBEAT_SECONDS);
    }

    /** Intervalo de heartbeat configurável (testes usam 1 s para exercitar a detecção de fluxo mudo). */
    public DahuaEventStream(HostPolicy hosts, DeviceSecrets secrets, int heartbeatSeconds) {
        this.hosts = hosts;
        this.secrets = secrets;
        this.heartbeatSeconds = heartbeatSeconds;
    }

    /** Bloqueia lendo o fluxo até ele cair, ficar mudo ou a thread ser interrompida. */
    public void consume(DeviceDescriptor device, Listener listener) throws DeviceCommunicationException {
        String user = env(device, "userEnv");
        String password = env(device, "passwordEnv");
        if (user == null || password == null) {
            throw new DeviceCommunicationException("Device credentials are not configured");
        }
        String codes = device.config().getOrDefault("eventCodes", "AccessControl,DoorStatus");
        if (!codes.matches("[A-Za-z0-9,]{1,120}")) {
            throw new DeviceCommunicationException("Invalid eventCodes");
        }
        URI uri = hosts.requireAllowed(device.config().getOrDefault("baseUrl", ""),
                "/cgi-bin/eventManager.cgi?action=attach&codes=[" + codes + "]&heartbeat=" + heartbeatSeconds);
        try {
            HttpResponse<InputStream> response = open(uri, user, password);
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new DeviceCommunicationException("Event stream rejected: HTTP " + response.statusCode());
            }
            read(response.body(), listener);
        } catch (IOException e) {
            throw new DeviceCommunicationException("Event stream interrupted", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceCommunicationException("Interrupted", e);
        }
    }

    private HttpResponse<InputStream> open(URI uri, String user, String password)
            throws IOException, InterruptedException, DeviceCommunicationException {
        HttpResponse<InputStream> first = client.send(request(uri).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (first.statusCode() != 401) {
            return first;
        }
        first.body().close();
        String path = uri.getRawPath() + "?" + uri.getRawQuery();
        Optional<String> auth = first.headers().allValues("WWW-Authenticate").stream()
                .map(c -> DigestAuth.authorize(c, "GET", path, user, password))
                .flatMap(Optional::stream).findFirst();
        if (auth.isEmpty()) {
            throw new DeviceCommunicationException("Device did not offer Digest authentication");
        }
        return client.send(request(uri).header("Authorization", auth.get()).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET();
    }

    private void read(InputStream in, Listener listener) throws IOException {
        long staleAfterNanos = Duration.ofSeconds(3L * heartbeatSeconds).toNanos();
        long[] last = {System.nanoTime()};
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1000);
                    if (System.nanoTime() - last[0] > staleAfterNanos) {
                        in.close();
                        return;
                    }
                }
            } catch (InterruptedException | IOException e) {
                // encerrado
            }
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                last[0] = System.nanoTime();
                listener.alive();
                parse(line).ifPresent(listener::event);
            }
        } finally {
            watchdog.interrupt();
        }
    }

    /** Interpreta uma linha do fluxo; vazio quando não é uma linha de evento. */
    static Optional<Event> parse(String line) {
        if (!line.startsWith("Code=")) {
            return Optional.empty();
        }
        String[] parts = line.split(";", 4);
        String code = parts[0].substring(5);
        String action = field(parts, 1, "action=");
        String index = field(parts, 2, "index=");
        String data = parts.length > 3 && parts[3].startsWith("data=") ? parts[3].substring(5) : null;
        return code.isBlank() ? Optional.empty() : Optional.of(new Event(code, action, index, data));
    }

    private static String field(String[] parts, int i, String prefix) {
        return parts.length > i && parts[i].startsWith(prefix) ? parts[i].substring(prefix.length()) : "";
    }

    private String env(DeviceDescriptor device, String key) {
        String name = device.config().get(key);
        String value = name == null ? null : secrets.get(name);
        return value == null || value.isBlank() ? null : value;
    }
}
