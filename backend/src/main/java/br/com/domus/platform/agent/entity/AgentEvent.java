package br.com.domus.platform.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Evento reportado pelo agente; o identificador é gerado no agente e torna o reenvio idempotente. */
@Entity
@Table(name = "agent_event")
public class AgentEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(name = "credential_id", updatable = false)
    private UUID credentialId;

    @Column(name = "device_id", updatable = false)
    private UUID deviceId;

    @Column(name = "command_id", updatable = false)
    private UUID commandId;

    @Column(updatable = false)
    private String entrance;

    @Column(updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected AgentEvent() {
    }

    /** Construtor para criação de um(a) agent event. */
    public AgentEvent(UUID eventId, UUID agentId, UUID condominiumId, String type, UUID credentialId, UUID deviceId,
                      UUID commandId, String entrance, String reason, Instant occurredAt, Instant receivedAt) {
        this.eventId = eventId;
        this.agentId = agentId;
        this.condominiumId = condominiumId;
        this.type = type;
        this.credentialId = credentialId;
        this.deviceId = deviceId;
        this.commandId = commandId;
        this.entrance = entrance;
        this.reason = reason;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    /** Retorna o campo event id. */
    public UUID getEventId() {
        return eventId;
    }

    /** Retorna o campo agent id. */
    public UUID getAgentId() {
        return agentId;
    }

    /** Retorna o campo type. */
    public String getType() {
        return type;
    }

    /** Retorna o campo credential id. */
    public UUID getCredentialId() {
        return credentialId;
    }

    /** Retorna o dispositivo. */
    public UUID getDeviceId() {
        return deviceId;
    }

    /** Retorna o campo command id. */
    public UUID getCommandId() {
        return commandId;
    }

    /** Retorna o ponto de acesso. */
    public String getEntrance() {
        return entrance;
    }

    /** Retorna o campo reason. */
    public String getReason() {
        return reason;
    }

    /** Retorna o campo occurred at. */
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
