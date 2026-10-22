package br.com.domus.platform.devices.adapter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Simulador para desenvolvimento sem hardware. Comportamento via {@code config.behavior}:
 * CONFIRM (aceita e emite DOOR_OPENED, como um equipamento real), SILENT (aceita e nunca confirma),
 * REJECT (recusa), UNREACHABLE (falha de comunicação). Desligado por padrão ({@code portaria.devices.simulator-enabled}).
 */
@Component
@ConditionalOnProperty(name = "portaria.devices.simulator-enabled", havingValue = "true")
public class SimulatorAdapter implements DeviceAdapter {

    private final DeviceEventSink sink;
    private final long confirmDelayMs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "device-simulator");
        t.setDaemon(true);
        return t;
    });

    public SimulatorAdapter(@Lazy DeviceEventSink sink,
                            @Value("${portaria.devices.simulator-confirm-delay-ms:300}") long confirmDelayMs) {
        this.sink = sink;
        this.confirmDelayMs = confirmDelayMs;
    }

    @Override
    public String type() {
        return "SIMULATOR";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.STATUS, Capability.OPEN_DOOR, Capability.EVENTS);
    }

    @Override
    public StatusReport getStatus(DeviceDescriptor device) {
        return "UNREACHABLE".equals(behavior(device))
                ? new StatusReport(Health.OFFLINE, "simulated unreachable")
                : new StatusReport(Health.ONLINE, "simulated");
    }

    @Override
    public CommandReceipt requestAccess(DeviceDescriptor device, AccessRequest request)
            throws DeviceCommunicationException {
        switch (behavior(device)) {
            case "UNREACHABLE" -> throw new DeviceCommunicationException("simulated connection failure");
            case "REJECT" -> {
                return new CommandReceipt(false, "simulated rejection");
            }
            case "SILENT" -> {
                return new CommandReceipt(true, "simulated accepted, no confirmation will follow");
            }
            default -> {
                UUID commandId = request.commandId();
                scheduler.schedule(() -> sink.onTrustedEvent(device.id(), device.condominiumId(), "DOOR_OPENED",
                        commandId), confirmDelayMs, TimeUnit.MILLISECONDS);
                return new CommandReceipt(true, "simulated accepted");
            }
        }
    }

    private static String behavior(DeviceDescriptor device) {
        return device.config().getOrDefault("behavior", "CONFIRM");
    }
}
