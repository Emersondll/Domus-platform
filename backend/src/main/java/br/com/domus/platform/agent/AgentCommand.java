package br.com.domus.platform.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Comando de abertura aguardando o agente buscá-lo. Expira rápido: um comando velho nunca é executado. */
@Entity
@Table(name = "agent_command")
public class AgentCommand {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(nullable = false, updatable = false)
    private String entrance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected AgentCommand() {
    }

    public AgentCommand(UUID commandId, UUID agentId, UUID condominiumId, UUID deviceId, String entrance,
                        Instant createdAt, Instant expiresAt) {
        this.id = commandId;
        this.agentId = agentId;
        this.condominiumId = condominiumId;
        this.deviceId = deviceId;
        this.entrance = entrance;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void deliver(Instant when) {
        this.deliveredAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getEntrance() {
        return entrance;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
