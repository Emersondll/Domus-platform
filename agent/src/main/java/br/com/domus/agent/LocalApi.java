package br.com.domus.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * API local (somente 127.0.0.1) usada pelo leitor/quiosque da portaria: valida um QR Code em contingência e expõe o
 * estado do agente. Exige a chave local; corpo limitado a 4 KB.
 */
public final class LocalApi implements AutoCloseable {

    private final HttpServer server;
    private final String localKey;
    private final OfflineAccessService access;
    private final LocalStore store;
    private final ConnectivityMonitor monitor;

    public LocalApi(int port, String localKey, OfflineAccessService access, LocalStore store, ConnectivityMonitor monitor)
            throws IOException {
        this.localKey = localKey;
        this.access = access;
        this.store = store;
        this.monitor = monitor;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/validate", this::validate);
        server.createContext("/status", this::status);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void validate(HttpExchange ex) throws IOException {
        try (ex) {
            if (!authorized(ex)) {
                reply(ex, 401, Map.of("error", "unauthorized"));
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                reply(ex, 405, Map.of("error", "method not allowed"));
                return;
            }
            byte[] raw = ex.getRequestBody().readNBytes(4097);
            if (raw.length > 4096) {
                reply(ex, 413, Map.of("error", "payload too large"));
                return;
            }
            JsonNode n;
            try {
                n = Json.MAPPER.readTree(new String(raw, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                reply(ex, 400, Map.of("error", "invalid json"));
                return;
            }
            String token = n.has("token") ? n.get("token").asString() : null;
            String entrance = n.has("entrance") ? n.get("entrance").asString() : null;
            OfflineAccessService.Decision d = access.decide(token, entrance);
            reply(ex, 200, Map.of("granted", d.granted(), "reason", d.reason()));
        }
    }

    private void status(HttpExchange ex) throws IOException {
        try (ex) {
            if (!authorized(ex)) {
                reply(ex, 401, Map.of("error", "unauthorized"));
                return;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("connectivity", monitor.isOnline() ? "ONLINE" : "OFFLINE");
            s.put("lastSuccess", monitor.lastSuccess() == null ? null : monitor.lastSuccess().toString());
            s.put("storageWritable", store.isWritable());
            s.put("recoveredFromCorruption", store.recoveredFromCorruption());
            try {
                s.put("backlog", store.backlog());
            } catch (LocalStore.StorageException e) {
                s.put("backlog", null);
            }
            reply(ex, 200, s);
        }
    }

    private boolean authorized(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst("X-Local-Key");
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                localKey.getBytes(StandardCharsets.UTF_8));
    }

    private static void reply(HttpExchange ex, int code, Map<String, ?> body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
