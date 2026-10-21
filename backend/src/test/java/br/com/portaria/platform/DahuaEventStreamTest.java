package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.portaria.platform.devices.adapter.DahuaEventStream;
import br.com.portaria.platform.devices.adapter.DeviceAdapter.DeviceCommunicationException;
import br.com.portaria.platform.devices.adapter.DeviceAdapter.DeviceDescriptor;
import br.com.portaria.platform.devices.adapter.HostPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Cliente do fluxo de eventos contra o equipamento falso (Digest real, multipart, queda e silêncio). */
class DahuaEventStreamTest {

    private FakeDahuaDevice device;
    private final List<DahuaEventStream.Event> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger alive = new AtomicInteger();
    private final DahuaEventStream.Listener listener = new DahuaEventStream.Listener() {
        public void alive() {
            alive.incrementAndGet();
        }

        public void event(DahuaEventStream.Event e) {
            received.add(e);
        }
    };

    @BeforeEach
    void start() throws Exception {
        device = new FakeDahuaDevice("secret-pass");
    }

    @AfterEach
    void stop() {
        device.stop();
    }

    private DeviceDescriptor descriptor() {
        return new DeviceDescriptor(UUID.randomUUID(), UUID.randomUUID(), "Portão", Map.of("baseUrl", device.baseUrl(),
                "userEnv", "U", "passwordEnv", "P"));
    }

    private DahuaEventStream stream(String password, int heartbeat) {
        return new DahuaEventStream(new HostPolicy("127.0.0.1"),
                name -> "U".equals(name) ? "admin" : "P".equals(name) ? password : null, heartbeat);
    }

    private Thread consumeInBackground(DahuaEventStream s, List<Throwable> errors) {
        return Thread.ofVirtual().start(() -> {
            try {
                s.consume(descriptor(), listener);
            } catch (Throwable t) {
                errors.add(t);
            }
        });
    }

    @Test
    void eventsArriveThroughDigestEvenWhenALineIsSplitAcrossWrites() {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread t = consumeInBackground(stream("secret-pass", 5), errors);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> device.streamCount() == 1);

        device.push("--myboundary\r\nContent-Type: text/plain\r\nContent-Length: 70\r\n\r\nCode=AccessControl;act");
        device.push("ion=Pulse;index=0;data={\"Status\":1}\r\n\r\n");
        device.pushEvent("Code=DoorStatus;action=Pulse;index=1;data={\"Status\":\"Open\"}");

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 2);
        assertThat(received.get(0).code()).isEqualTo("AccessControl");
        assertThat(received.get(0).action()).isEqualTo("Pulse");
        assertThat(received.get(0).data()).isEqualTo("{\"Status\":1}");
        assertThat(received.get(1).code()).isEqualTo("DoorStatus");
        assertThat(alive.get()).isGreaterThan(2); // boundary e cabeçalhos também provam que está vivo
        device.dropStreams();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !t.isAlive());
        assertThat(errors).isEmpty();
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThatThrownBy(() -> stream("wrong", 5).consume(descriptor(), listener))
                .isInstanceOf(DeviceCommunicationException.class);
        assertThat(received).isEmpty();
    }

    @Test
    void aSilentStreamIsDeclaredDeadAfterThreeHeartbeatIntervals() {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();
        Thread t = consumeInBackground(stream("secret-pass", 1), errors);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !t.isAlive());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(9));
    }
}
