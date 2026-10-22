package br.com.domus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Classificação das falhas de rede no cliente HTTP real: DNS, conexão recusada, timeout, 5xx e credenciais. */
class HttpCentralClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpCentralClient clientFor(String base, Duration timeout) {
        return new HttpCentralClient(base, UUID.randomUUID(), () -> "key", timeout);
    }

    private String serve(int status, String body, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 9. Indisponibilidade de DNS
    @Test
    void dnsFailureIsReportedAsUnreachable() {
        HttpCentralClient c = clientFor("http://no-such-host.invalid:8081", Duration.ofSeconds(3));
        assertThatThrownBy(() -> c.fetchCommands(0)).isInstanceOf(CentralClient.UnreachableException.class);
    }

    @Test
    void connectionRefusedAndTimeoutAreUnreachable() throws Exception {
        assertThatThrownBy(() -> clientFor("http://127.0.0.1:1", Duration.ofSeconds(2)).fetchCommands(0))
                .isInstanceOf(CentralClient.UnreachableException.class);
        String slow = serve(200, "{\"commands\":[]}", 1500);
        assertThatThrownBy(() -> clientFor(slow, Duration.ofMillis(300)).fetchCommands(0))
                .isInstanceOf(CentralClient.UnreachableException.class);
    }

    @Test
    void serverErrorsAndRateLimitAreUnavailableAndAuthFailuresAreDistinct() throws Exception {
        String base = serve(503, "{}", 0);
        assertThatThrownBy(() -> clientFor(base, Duration.ofSeconds(2)).fetchCommands(0))
                .isInstanceOf(CentralClient.UnavailableException.class);
        server.stop(0);
        String forbidden = serve(403, "{}", 0);
        assertThatThrownBy(() -> clientFor(forbidden, Duration.ofSeconds(2)).fetchCommands(0))
                .isInstanceOf(CentralClient.AuthException.class);
    }

    @Test
    void parsesPolicyCommandsAndEventResultsFromTheRealApiShape() throws Exception {
        String none = serve(204, "", 0);
        assertThat(clientFor(none, Duration.ofSeconds(2)).fetchPolicy(3L, 10L, 0)).isEmpty();
        server.stop(0);
        UUID id = UUID.randomUUID();
        String body = "{\"commands\":[{\"commandId\":\"" + id + "\",\"deviceId\":\"" + UUID.randomUUID()
                + "\",\"entrance\":\"portaria\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}]}";
        String base = serve(200, body, 0);
        assertThat(clientFor(base, Duration.ofSeconds(2)).fetchCommands(0)).singleElement()
                .satisfies(c -> assertThat(c.commandId()).isEqualTo(id));
    }
}
