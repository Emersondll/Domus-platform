package br.com.domus.platform.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.domus.platform.devices.adapter.DeviceAdapter.AccessRequest;
import br.com.domus.platform.devices.adapter.DeviceAdapter.CommandReceipt;
import br.com.domus.platform.devices.adapter.DeviceAdapter.DeviceDescriptor;
import br.com.domus.platform.devices.adapter.DeviceAdapter.DeviceCommunicationException;
import br.com.domus.platform.devices.adapter.DeviceAdapter.Health;
import br.com.domus.platform.devices.adapter.GenericHttpAdapter;
import br.com.domus.platform.devices.adapter.HostPolicy;
import br.com.domus.platform.devices.adapter.OnvifAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Defesa em profundidade contra SSRF: endereço resolvido, DNS que muda, redirecionamento e resposta gigante (sem Spring). */
class SsrfDefenseTest {

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal); // literal: não consulta DNS
    }

    private static HostPolicy policyResolvingTo(String hosts, String... literals) {
        return new HostPolicy(hosts, name -> {
            InetAddress[] out = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                out[i] = ip(literals[i]);
            }
            return out;
        });
    }

    @Test
    void cloudMetadataAndLinkLocalAreRefusedEvenWhenTheHostIsOnTheList() {
        for (String forbidden : new String[]{"169.254.169.254", "169.254.0.7", "fe80::1", "fd00:ec2::254", "100.100.100.200",
                "0.0.0.0", "::", "224.0.0.1", "ff02::1"}) {
            HostPolicy policy = policyResolvingTo("camera.test", forbidden);
            assertThatThrownBy(() -> policy.requireAllowed("http://camera.test", "/x"))
                    .as(forbidden).isInstanceOf(DeviceCommunicationException.class);
            assertThatThrownBy(() -> policy.requireAllowedRtsp("rtsp://camera.test/stream"))
                    .as(forbidden).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void privateAndLoopbackAddressesStillWorkWhenTheListAllowsThem() throws Exception {
        for (String ok : new String[]{"192.168.1.20", "10.0.0.5", "172.16.4.2", "127.0.0.1", "::1"}) {
            HostPolicy policy = policyResolvingTo("camera.test", ok);
            assertThat(policy.requireAllowed("http://camera.test", "/x").getHost()).isEqualTo("camera.test");
            assertThat(policy.requireAllowedRtsp("rtsp://camera.test/stream")).isNotNull();
        }
    }

    @Test
    void oneForbiddenAddressAmongSeveralRefusesTheHost() {
        HostPolicy policy = policyResolvingTo("camera.test", "192.168.1.20", "169.254.169.254");
        assertThatThrownBy(() -> policy.requireAllowed("http://camera.test", "/x")).isInstanceOf(DeviceCommunicationException.class);
    }

    @Test
    void aNameThatChangesItsAnswerIsCheckedAgainOnEveryRequest() throws Exception {
        AtomicReference<String> answer = new AtomicReference<>("192.168.1.20");
        HostPolicy policy = new HostPolicy("camera.test", name -> new InetAddress[]{ip(answer.get())});
        assertThat(policy.requireAllowed("http://camera.test", "/x")).isNotNull();
        answer.set("169.254.169.254"); // DNS rebinding: o mesmo nome passa a apontar para o serviço de metadados
        assertThatThrownBy(() -> policy.requireAllowed("http://camera.test", "/x")).isInstanceOf(DeviceCommunicationException.class);
    }

    @Test
    void unresolvableNamesAndHostsOffTheListAreRefused() {
        HostPolicy broken = new HostPolicy("camera.test", name -> {
            throw new UnknownHostException(name);
        });
        assertThatThrownBy(() -> broken.requireAllowed("http://camera.test", "/x")).isInstanceOf(DeviceCommunicationException.class);
        HostPolicy other = policyResolvingTo("camera.test", "192.168.1.20");
        assertThatThrownBy(() -> other.requireAllowed("http://evil.test", "/x")).isInstanceOf(DeviceCommunicationException.class);
    }

    @Test
    void credentialsInsideTheUrlAreRefused() {
        HostPolicy policy = policyResolvingTo("camera.test", "192.168.1.20");
        assertThatThrownBy(() -> policy.requireAllowed("http://admin:secret@camera.test", "/x"))
                .isInstanceOf(DeviceCommunicationException.class);
    }

    @Test
    void redirectsAreNeverFollowed() throws Exception {
        AtomicInteger hitsOnTarget = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", ex -> {
            hitsOnTarget.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        target.start();
        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/internal");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        redirector.start();
        try {
            GenericHttpAdapter adapter = new GenericHttpAdapter(new HostPolicy("127.0.0.1"));
            DeviceDescriptor device = new DeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), "P",
                    Map.of("baseUrl", "http://127.0.0.1:" + redirector.getAddress().getPort(), "openPath", "/open"));
            CommandReceipt receipt = adapter.requestAccess(device, new AccessRequest(UUID.randomUUID(), "key"));
            assertThat(receipt.accepted()).isFalse();
            assertThat(hitsOnTarget.get()).as("o destino do redirecionamento não pode ser alcançado").isZero();
        } finally {
            redirector.stop(0);
            target.stop(0);
        }
    }

    @Test
    void anOversizedDeviceResponseIsRefusedInsteadOfBufferedInMemory() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] chunk = new byte[64 * 1024];
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 64; i++) { // 4 MB, bem acima do teto de 256 KB
                    out.write(chunk);
                }
            } catch (java.io.IOException ignored) {
                // o cliente fechou ao passar do teto
            }
        });
        server.start();
        try {
            OnvifAdapter adapter = new OnvifAdapter(new HostPolicy("127.0.0.1"));
            var report = adapter.getStatus(new DeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), "P",
                    Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort())));
            assertThat(report.health()).isEqualTo(Health.OFFLINE);
            assertThat(report.detail()).contains("exceeds");
        } finally {
            server.stop(0);
        }
    }
}
