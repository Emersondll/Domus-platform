package br.com.domus.platform.agent.entity;

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

    /** Construtor para criação de um(a) agent command. */
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

    /**
     * Marca o comando como entregue ao agente.
     *
     * @param when instante do fato
     */
    public void deliver(Instant when) {
        this.deliveredAt = when;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o dispositivo. */
    public UUID getDeviceId() {
        return deviceId;
    }

    /** Retorna o ponto de acesso. */
    public String getEntrance() {
        return entrance;
    }

    /** Retorna o instante de expiração. */
    public Instant getExpiresAt() {
        return expiresAt;
    }
}
