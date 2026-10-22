package br.com.domus.platform.devices;

import br.com.domus.platform.devices.adapter.DahuaEventMapper;
import br.com.domus.platform.devices.adapter.DahuaEventStream;
import br.com.domus.platform.devices.adapter.DeviceAdapter.DeviceDescriptor;
import br.com.domus.platform.devices.adapter.DeviceSecrets;
import br.com.domus.platform.devices.adapter.HostPolicy;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mantém uma conexão de eventos por controladora {@code DAHUA_CGI} e entrega os eventos traduzidos a
 * {@link DeviceEventService}. Reconecta com espera crescente (1 s a 60 s). Dispositivo novo, removido ou com
 * configuração alterada é reconciliado a cada 30 s. Com várias instâncias da aplicação cada uma assina o equipamento
 * (confirmação é idempotente); um limite de conexões por equipamento exige eleição de líder (pendência documentada).
 */
@Component
public class DahuaEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(DahuaEventSubscriber.class);
    private static final Duration LIVENESS_REPORT = Duration.ofSeconds(60);

    private record Worker(Map<String, String> config, Thread thread) {
    }

    private final DeviceRepository devices;
    private final DeviceEventService events;
    private final DahuaEventStream stream;
    private final boolean enabled;
    private final Map<UUID, Worker> workers = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public DahuaEventSubscriber(DeviceRepository devices, DeviceEventService events, HostPolicy hosts, DeviceSecrets secrets,
                                @Value("${portaria.devices.dahua-events.enabled:true}") boolean enabled) {
        this.devices = devices;
        this.events = events;
        this.stream = new DahuaEventStream(hosts, secrets);
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${portaria.devices.dahua-events.reconcile-ms:30000}")
    public void reconcile() {
        if (!enabled || !running) {
            return;
        }
        Map<UUID, Device> wanted = devices.findByAdapter("DAHUA_CGI").stream()
                .filter(d -> !"off".equalsIgnoreCase(d.getConfig().get("events")))
                .collect(Collectors.toMap(Device::getId, d -> d));
        for (var entry : Set.copyOf(workers.entrySet())) {
            Device d = wanted.get(entry.getKey());
            if (d == null || !d.getConfig().equals(entry.getValue().config())) {
                stop(entry.getKey());
            }
        }
        wanted.values().forEach(d -> workers.computeIfAbsent(d.getId(), id -> start(d)));
    }

    private Worker start(Device device) {
        DeviceDescriptor descriptor = DeviceService.descriptor(device);
        Thread thread = Thread.ofVirtual().name("dahua-events-" + device.getId()).start(() -> loop(descriptor));
        return new Worker(Map.copyOf(device.getConfig()), thread);
    }

    private void stop(UUID id) {
        Worker w = workers.remove(id);
        if (w != null) {
            w.thread().interrupt();
        }
    }

    private void loop(DeviceDescriptor device) {
        long backoffMs = 1000;
        while (running && !Thread.currentThread().isInterrupted()) {
            boolean[] gotData = {false};
            long[] lastReport = {0};
            try {
                stream.consume(device, new DahuaEventStream.Listener() {
                    @Override
                    public void alive() {
                        gotData[0] = true;
                        long now = System.nanoTime();
                        if (lastReport[0] == 0 || now - lastReport[0] > LIVENESS_REPORT.toNanos()) {
                            lastReport[0] = now;
                            deliver(device, "HEARTBEAT", null);
                        }
                    }

                    @Override
                    public void event(DahuaEventStream.Event event) {
                        DahuaEventMapper.map(event).ifPresent(m -> deliver(device, m.type(), m.commandId()));
                    }
                });
            } catch (Exception e) {
                log.warn("Event stream of device {} ended: {}", device.id(), e.getMessage());
            }
            if (gotData[0]) {
                backoffMs = 1000;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 60_000);
        }
    }

    private void deliver(DeviceDescriptor device, String type, UUID commandId) {
        try {
            events.onTrustedEvent(device.id(), device.condominiumId(), type, commandId);
        } catch (RuntimeException e) {
            log.warn("Could not record {} event of device {}: {}", type, device.id(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        running = false;
        Set.copyOf(workers.keySet()).forEach(this::stop);
    }
}
