package br.com.portaria.platform.devices.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Controladoras da família Dahua/Intelbras pela API CGI com autenticação Digest. Configuração não secreta em
 * {@code device.config}: {@code baseUrl}, {@code channel} (padrão 1), {@code userEnv} e {@code passwordEnv} (nomes
 * das variáveis de ambiente com as credenciais; nunca o valor).
 *
 * <p>Abertura: {@code GET /cgi-bin/accessControl.cgi?action=openDoor&channel=N&UserID=..&Type=Remote}. O equipamento
 * responde {@code OK} no corpo quando aceita; isso significa <b>aceito</b>, não aberto. A abertura só é confirmada por
 * evento do dispositivo (que chega pelo endpoint de eventos ou pelo agente local), nunca por esta resposta.
 *
 * <p><b>Não validado com hardware real.</b> Foi escrito a partir da documentação pública da API e testado contra um
 * servidor local que imita o protocolo; firmware e modelos diferem, então a primeira instalação precisa de teste em campo.
 */
@Component
public class DahuaCgiAdapter implements DeviceAdapter {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final HostPolicy hosts;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final Function<String, String> environment;

    @Autowired
    public DahuaCgiAdapter(HostPolicy hosts) {
        this(hosts, System::getenv);
    }

    /** Permite injetar a origem das credenciais (testes). */
    public DahuaCgiAdapter(HostPolicy hosts, Function<String, String> environment) {
        this.hosts = hosts;
        this.environment = environment;
    }

    @Override
    public String type() {
        return "DAHUA_CGI";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.STATUS, Capability.OPEN_DOOR);
    }

    @Override
    public StatusReport getStatus(DeviceDescriptor device) {
        try {
            URI uri = hosts.requireAllowed(device.config().getOrDefault("baseUrl", ""),
                    "/cgi-bin/magicBox.cgi?action=getSystemInfo");
            Response r = get(uri, device);
            return r.status() / 100 == 2
                    ? new StatusReport(Health.ONLINE, "HTTP " + r.status())
                    : new StatusReport(Health.OFFLINE, "HTTP " + r.status());
        } catch (DeviceCommunicationException e) {
            return new StatusReport(Health.OFFLINE, e.getMessage());
        }
    }

    @Override
    public CommandReceipt requestAccess(DeviceDescriptor device, AccessRequest request)
            throws DeviceCommunicationException {
        String channel = device.config().getOrDefault("channel", "1");
        if (!channel.matches("\\d{1,3}")) {
            throw new DeviceCommunicationException("Invalid channel");
        }
        String userId = URLEncoder.encode("portaria-" + request.commandId(), StandardCharsets.UTF_8);
        URI uri = hosts.requireAllowed(device.config().getOrDefault("baseUrl", ""),
                "/cgi-bin/accessControl.cgi?action=openDoor&channel=" + channel + "&UserID=" + userId + "&Type=Remote");
        Response r = get(uri, device);
        boolean accepted = r.status() / 100 == 2 && r.body().trim().equalsIgnoreCase("OK");
        return new CommandReceipt(accepted, "HTTP " + r.status() + (accepted ? "" : " body=" + abbreviate(r.body())));
    }

    private record Response(int status, String body) {
    }

    /** GET com Digest: a primeira chamada recebe o desafio 401 e a segunda envia a prova. */
    private Response get(URI uri, DeviceDescriptor device) throws DeviceCommunicationException {
        String user = env(device, "userEnv");
        String password = env(device, "passwordEnv");
        if (user == null || password == null) {
            throw new DeviceCommunicationException("Device credentials are not configured");
        }
        try {
            HttpResponse<String> first = client.send(base(uri).build(), HttpResponse.BodyHandlers.ofString());
            if (first.statusCode() != 401) {
                return new Response(first.statusCode(), first.body());
            }
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            Optional<String> auth = first.headers().allValues("WWW-Authenticate").stream()
                    .map(c -> DigestAuth.authorize(c, "GET", path, user, password))
                    .flatMap(Optional::stream).findFirst();
            if (auth.isEmpty()) {
                throw new DeviceCommunicationException("Device did not offer Digest authentication");
            }
            HttpResponse<String> second = client.send(base(uri).header("Authorization", auth.get()).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(second.statusCode(), second.body());
        } catch (IOException e) {
            throw new DeviceCommunicationException("Device unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeviceCommunicationException("Interrupted", e);
        }
    }

    private HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
    }

    private String env(DeviceDescriptor device, String key) {
        String name = device.config().get(key);
        String value = name == null ? null : environment.apply(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String abbreviate(String s) {
        String t = s.strip();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
