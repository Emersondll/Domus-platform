package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.domus.platform.devices.adapter.DahuaCgiAdapter;
import br.com.domus.platform.devices.adapter.DeviceAdapter.AccessRequest;
import br.com.domus.platform.devices.adapter.DeviceAdapter.CommandReceipt;
import br.com.domus.platform.devices.adapter.DeviceAdapter.DeviceCommunicationException;
import br.com.domus.platform.devices.adapter.DeviceAdapter.DeviceDescriptor;
import br.com.domus.platform.devices.adapter.DeviceAdapter.Health;
import br.com.domus.platform.devices.adapter.HostPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Protocolo CGI + Digest contra um servidor local que imita a controladora (o equipamento real não foi testado).
 * O servidor valida a prova Digest de verdade, então um erro de cálculo no adaptador faz o teste falhar.
 */
class DahuaCgiAdapterTest {

    private static final String REALM = "Login to DEVICE";
    private static final String NONCE = "abc123nonce";

    private HttpServer server;
    private final List<String> opened = new CopyOnWriteArrayList<>();
    private volatile String openBody = "OK";
    private volatile int openStatus = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/", ex -> {
            String uri = ex.getRequestURI().toString();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !validDigest(auth, ex.getRequestMethod(), uri)) {
                ex.getResponseHeaders().add("WWW-Authenticate",
                        "Digest realm=\"" + REALM + "\", qop=\"auth\", nonce=\"" + NONCE + "\", opaque=\"op\"");
                ex.sendResponseHeaders(401, -1);
                ex.close();
                return;
            }
            if (uri.contains("action=openDoor")) {
                opened.add(uri);
            }
            byte[] body = (uri.contains("openDoor") ? openBody : "appName=Device").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(uri.contains("openDoor") ? openStatus : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private boolean validDigest(String header, String method, String uri) {
        Map<String, String> p = new java.util.HashMap<>();
        for (String part : header.substring(7).split(",\\s*")) {
            String[] kv = part.split("=", 2);
            p.put(kv[0].trim(), kv[1].replace("\"", ""));
        }
        if (!"admin".equals(p.get("username")) || !p.get("uri").equals(uri) || !NONCE.equals(p.get("nonce"))) {
            return false;
        }
        String ha1 = md5("admin:" + REALM + ":secret-pass");
        String ha2 = md5(method + ":" + uri);
        String expected = md5(ha1 + ":" + NONCE + ":" + p.get("nc") + ":" + p.get("cnonce") + ":auth:" + ha2);
        return expected.equals(p.get("response"));
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DahuaCgiAdapter adapter(String password) {
        return new DahuaCgiAdapter(new HostPolicy("127.0.0.1"),
                name -> "DEV_USER".equals(name) ? "admin" : "DEV_PASS".equals(name) ? password : null);
    }

    private DeviceDescriptor device(Map<String, String> extra) {
        Map<String, String> cfg = new java.util.HashMap<>(Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
                "userEnv", "DEV_USER", "passwordEnv", "DEV_PASS", "channel", "2"));
        cfg.putAll(extra);
        return new DeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), "Portão", cfg);
    }

    @Test
    void acceptedCommandSendsDigestProofAndChannel() throws Exception {
        UUID commandId = UUID.randomUUID();
        CommandReceipt receipt = adapter("secret-pass").requestAccess(device(Map.of()), new AccessRequest(commandId, "k"));

        assertThat(receipt.accepted()).isTrue();
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0)).contains("channel=2").contains("Type=Remote").contains(commandId.toString());
    }

    @Test
    void wrongPasswordIsNeverAccepted() throws Exception {
        CommandReceipt receipt = adapter("wrong").requestAccess(device(Map.of()), new AccessRequest(UUID.randomUUID(), "k"));

        assertThat(receipt.accepted()).isFalse();
        assertThat(opened).isEmpty();
    }

    @Test
    void http200WithoutOkBodyIsNotAccepted() throws Exception {
        openBody = "Error\r\n";
        CommandReceipt receipt = adapter("secret-pass").requestAccess(device(Map.of()), new AccessRequest(UUID.randomUUID(), "k"));

        assertThat(receipt.accepted()).isFalse();
    }

    @Test
    void statusIsOnlineWithValidCredentialsAndOfflineOtherwise() {
        assertThat(adapter("secret-pass").getStatus(device(Map.of())).health()).isEqualTo(Health.ONLINE);
        assertThat(adapter("wrong").getStatus(device(Map.of())).health()).isEqualTo(Health.OFFLINE);
    }

    @Test
    void missingCredentialsAndDisallowedHostAreRejectedBeforeAnyRequest() {
        DahuaCgiAdapter noCreds = new DahuaCgiAdapter(new HostPolicy("127.0.0.1"), name -> null);
        assertThatThrownBy(() -> noCreds.requestAccess(device(Map.of()), new AccessRequest(UUID.randomUUID(), "k")))
                .isInstanceOf(DeviceCommunicationException.class);

        DahuaCgiAdapter strict = new DahuaCgiAdapter(new HostPolicy(""), name -> "x");
        assertThatThrownBy(() -> strict.requestAccess(device(Map.of()), new AccessRequest(UUID.randomUUID(), "k")))
                .isInstanceOf(DeviceCommunicationException.class).hasMessageContaining("not allowed");
        assertThat(opened).isEmpty();
    }

    @Test
    void channelIsValidatedToBlockParameterInjection() {
        assertThatThrownBy(() -> adapter("secret-pass").requestAccess(device(Map.of("channel", "1&action=x")),
                new AccessRequest(UUID.randomUUID(), "k"))).isInstanceOf(DeviceCommunicationException.class);
        assertThat(opened).isEmpty();
    }
}
