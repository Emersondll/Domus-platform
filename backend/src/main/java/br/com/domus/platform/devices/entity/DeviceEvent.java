package br.com.domus.platform.devices.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Evento reportado por um dispositivo (ou pelo agente local em nome dele). */
@Entity
@Table(name = "device_event")
public class DeviceEvent {

    @Id
    private UUID id;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(name = "command_id", updatable = false)
    private UUID commandId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected DeviceEvent() {
    }

    public DeviceEvent(UUID deviceId, UUID condominiumId, String type, UUID commandId, Instant occurredAt,
                       Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.deviceId = deviceId;
        this.condominiumId = condominiumId;
        this.type = type;
        this.commandId = commandId;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public UUID getCommandId() {
        return commandId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
