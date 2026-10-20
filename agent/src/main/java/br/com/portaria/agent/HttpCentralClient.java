package br.com.portaria.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** Implementação HTTP do {@link CentralClient}, com timeouts e mapeamento explícito das falhas. */
public final class HttpCentralClient implements CentralClient {

    private final String baseUrl;
    private final UUID agentId;
    private final Supplier<String> apiKey;
    private final HttpClient http;
    private final java.time.Duration timeout;

    public HttpCentralClient(String baseUrl, UUID agentId, Supplier<String> apiKey, java.time.Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.agentId = agentId;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Optional<PolicyEnvelope> fetchPolicy(Long sinceVersion, Long ageSeconds, int backlog)
            throws UnreachableException, UnavailableException, AuthException {
        String query = sinceVersion == null ? "" : "?sinceVersion=" + sinceVersion + "&ageSeconds=" + ageSeconds;
        HttpResponse<String> r = send(request("/api/v1/agent/policy" + query, backlog).GET().build());
        if (r.statusCode() == 204) {
            return Optional.empty();
        }
        JsonNode n = Json.MAPPER.readTree(r.body());
        return Optional.of(new PolicyEnvelope(n.get("policy").asString(), n.get("signature").asString()));
    }

    @Override
    public List<EventResult> postEvents(List<OutboxEvent> events, int backlog)
            throws UnreachableException, UnavailableException, AuthException {
        String body = Json.MAPPER.writeValueAsString(Map.of("events", events.stream().map(OutboxEvent::toMap).toList()));
        HttpResponse<String> r = send(request("/api/v1/agent/events", backlog)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        List<EventResult> out = new ArrayList<>();
        for (JsonNode n : Json.MAPPER.readTree(r.body()).get("results")) {
            JsonNode detail = n.get("detail");
            out.add(new EventResult(UUID.fromString(n.get("eventId").asString()), n.get("status").asString(),
                    detail == null || detail.isNull() ? null : detail.asString()));
        }
        return out;
    }

    @Override
    public List<Command> fetchCommands(int backlog) throws UnreachableException, UnavailableException, AuthException {
        HttpResponse<String> r = send(request("/api/v1/agent/commands", backlog).GET().build());
        List<Command> out = new ArrayList<>();
        for (JsonNode n : Json.MAPPER.readTree(r.body()).get("commands")) {
            out.add(new Command(UUID.fromString(n.get("commandId").asString()),
                    UUID.fromString(n.get("deviceId").asString()), n.get("entrance").asString(),
                    Instant.parse(n.get("expiresAt").asString())));
        }
        return out;
    }

    @Override
    public String rotateKey() throws UnreachableException, UnavailableException, AuthException {
        HttpResponse<String> r = send(request("/api/v1/agent/rotate-key", 0)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        return Json.MAPPER.readTree(r.body()).get("apiKey").asString();
    }

    private HttpRequest.Builder request(String path, int backlog) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
                .header("X-Agent-Id", agentId.toString()).header("X-Agent-Key", apiKey.get())
                .header("X-Agent-Backlog", Integer.toString(backlog));
    }

    private HttpResponse<String> send(HttpRequest request)
            throws UnreachableException, UnavailableException, AuthException {
        HttpResponse<String> r;
        try {
            r = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // UnknownHostException (DNS), ConnectException, HttpTimeoutException... tudo é "sem rede" para o agente.
            throw new UnreachableException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnreachableException("interrupted", e);
        }
        int code = r.statusCode();
        if (code == 401 || code == 403) {
            throw new AuthException("Central rejected agent credentials (HTTP " + code + ")");
        }
        if (code == 429 || code >= 500) {
            throw new UnavailableException("Central unavailable (HTTP " + code + ")");
        }
        if (code / 100 != 2) {
            throw new UnavailableException("Unexpected response (HTTP " + code + ")");
        }
        return r;
    }
}
