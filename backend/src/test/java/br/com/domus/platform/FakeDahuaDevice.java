package br.com.domus.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * Imita a controladora Dahua/Intelbras para os testes: valida a prova Digest de verdade, aceita {@code openDoor} e
 * mantém o fluxo {@code eventManager.cgi?action=attach} aberto para os testes empurrarem linhas. Não é o equipamento:
 * reproduz só o que a documentação pública e exemplos da comunidade descrevem.
 */
final class FakeDahuaDevice {

    static final String REALM = "Login to DEVICE";
    static final String NONCE = "abc123nonce";

    private record Stream(OutputStream out, CountDownLatch closed) {
    }

    private final HttpServer server;
    private final String password;
    final List<String> opened = new CopyOnWriteArrayList<>();
    private final List<Stream> streams = new CopyOnWriteArrayList<>();
    /** Quando verdadeiro, o openDoor gera o evento AccessControl devolvendo o UserID recebido. */
    volatile boolean echoUserIdOnOpen = false;
    volatile String openBody = "OK";

    FakeDahuaDevice(String password) throws IOException {
        this.password = password;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/cgi-bin/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int streamCount() {
        return streams.size();
    }

    void stop() {
        dropStreams();
        server.stop(0);
    }

    void dropStreams() {
        streams.forEach(s -> {
            try {
                s.out().close();
            } catch (IOException e) {
                // já fechado
            }
            s.closed().countDown();
        });
        streams.clear();
    }

    /** Escreve texto cru no fluxo, como o equipamento faria (pode ser metade de uma linha). */
    void push(String raw) {
        for (Stream s : streams) {
            try {
                s.out().write(raw.getBytes(StandardCharsets.UTF_8));
                s.out().flush();
            } catch (IOException e) {
                streams.remove(s);
            }
        }
    }

    void pushEvent(String line) {
        push("--myboundary\r\nContent-Type: text/plain\r\nContent-Length: " + line.length() + "\r\n\r\n" + line + "\r\n\r\n");
    }

    private void handle(HttpExchange ex) throws IOException {
        String uri = ex.getRequestURI().toString();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !validDigest(auth, ex.getRequestMethod(), uri)) {
            ex.getResponseHeaders().add("WWW-Authenticate",
                    "Digest realm=\"" + REALM + "\", qop=\"auth\", nonce=\"" + NONCE + "\", opaque=\"op\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
        }
        if (uri.contains("eventManager.cgi")) {
            ex.getResponseHeaders().add("Content-Type", "multipart/x-mixed-replace; boundary=myboundary");
            ex.sendResponseHeaders(200, 0);
            Stream s = new Stream(ex.getResponseBody(), new CountDownLatch(1));
            streams.add(s);
            try {
                s.closed().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
            return;
        }
        byte[] body;
        if (uri.contains("action=openDoor")) {
            opened.add(uri);
            body = openBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
            if (echoUserIdOnOpen) {
                String userId = URLDecoder.decode(uri.replaceAll(".*UserID=([^&]*).*", "$1"), StandardCharsets.UTF_8);
                pushEvent("Code=AccessControl;action=Pulse;index=0;data={\"Status\":1,\"UserID\":\"" + userId + "\"}");
            }
            return;
        }
        body = "appName=Device".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private boolean validDigest(String header, String method, String uri) {
        Map<String, String> p = new HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").matcher(header.substring(7));
        while (m.find()) {
            p.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        if (!"admin".equals(p.get("username")) || !uri.equals(p.get("uri")) || !NONCE.equals(p.get("nonce"))) {
            return false;
        }
        String ha1 = md5("admin:" + REALM + ":" + password);
        String ha2 = md5(method + ":" + uri);
        return md5(ha1 + ":" + NONCE + ":" + p.get("nc") + ":" + p.get("cnonce") + ":auth:" + ha2).equals(p.get("response"));
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
