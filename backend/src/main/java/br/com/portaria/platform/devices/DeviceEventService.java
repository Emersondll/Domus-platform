package br.com.portaria.platform.devices;

import br.com.portaria.platform.devices.adapter.DeviceEventSink;
import br.com.portaria.platform.shared.error.ForbiddenException;
import br.com.portaria.platform.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingestão de eventos de dispositivos, autenticada pela chave do próprio dispositivo (o agente local usa este
 * mesmo caminho). Um DOOR_OPENED correlacionado a um comando é a evidência que o leva a CONFIRMED.
 */
@Service
public class DeviceEventService implements DeviceEventSink {

    static final Set<String> TYPES = Set.of("DOOR_OPENED", "DOOR_CLOSED", "HEARTBEAT", "TAMPER", "ACCESS_DENIED");

    private final DeviceRepository devices;
    private final DeviceEventRepository events;
    private final AccessCommandRepository commands;
    private final Clock clock;

    public DeviceEventService(DeviceRepository devices, DeviceEventRepository events,
                              AccessCommandRepository commands, Clock clock) {
        this.devices = devices;
        this.events = events;
        this.commands = commands;
        this.clock = clock;
    }

    /** Evento vindo da rede: exige a chave de API do dispositivo. */
    @Transactional
    public void ingest(UUID deviceId, String apiKey, String type, UUID commandId, Instant occurredAt) {
        Device device = devices.findById(deviceId).orElseThrow(() -> new NotFoundException("Device not found"));
        if (apiKey == null || device.getApiKeyHash() == null || !MessageDigest.isEqual(
                DeviceService.sha256(apiKey).getBytes(StandardCharsets.UTF_8),
                device.getApiKeyHash().getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("Invalid device credentials");
        }
        record(device, type, commandId, occurredAt);
    }

    @Override
    @Transactional
    public void onTrustedEvent(UUID deviceId, UUID condominiumId, String type, UUID commandId) {
        Device device = devices.findByIdAndCondominiumId(deviceId, condominiumId).orElseThrow();
        record(device, type, commandId, Instant.now(clock));
    }

    /** O agente informa que não conseguiu executar o comando (controladora indisponível, comando vencido...). */
    @Transactional
    public void onCommandFailed(UUID commandId, UUID deviceId, String reason) {
        commands.findById(commandId).filter(c -> c.getDeviceId().equals(deviceId)).ifPresent(
                c -> commands.markFailed(commandId, reason == null ? "AGENT_FAILURE" : reason, Instant.now(clock)));
    }

    private void record(Device device, String type, UUID commandId, Instant occurredAt) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown event type");
        }
        Instant now = Instant.now(clock);
        // Só correlaciona com comando que pertence a este dispositivo: um evento não confirma comando alheio.
        UUID ownCommand = commandId == null ? null : commands.findById(commandId)
                .filter(c -> c.getDeviceId().equals(device.getId())).map(AccessCommand::getId).orElse(null);
        events.save(new DeviceEvent(device.getId(), device.getCondominiumId(), type, ownCommand, occurredAt, now));
        device.observe("ONLINE", now);
        if ("DOOR_OPENED".equals(type) && ownCommand != null) {
            commands.markConfirmed(ownCommand, device.getId(), "DEVICE_EVENT:DOOR_OPENED", now);
        }
    }
}
