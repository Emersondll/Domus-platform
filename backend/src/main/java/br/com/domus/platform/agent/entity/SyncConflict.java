package br.com.domus.platform.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Divergência detectada na reconciliação entre o que o agente fez offline e o estado da central. */
@Entity
@Table(name = "sync_conflict")
public class SyncConflict {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(updatable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    protected SyncConflict() {
    }

    /** Construtor para criação de um(a) sync conflict. */
    public SyncConflict(UUID eventId, UUID condominiumId, String kind, String detail, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.condominiumId = condominiumId;
        this.kind = kind;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    /**
     * Marca o conflito como resolvido pelo usuário.
     *
     * @param userId usuário
     * @param when instante do fato
     */
    public void resolve(UUID userId, Instant when) {
        this.resolvedBy = userId;
        this.resolvedAt = when;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo event id. */
    public UUID getEventId() {
        return eventId;
    }

    /** Retorna o tipo. */
    public String getKind() {
        return kind;
    }

    /** Retorna o detalhe. */
    public String getDetail() {
        return detail;
    }

    /** Retorna o instante de criação. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Retorna o instante da resolução. */
    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
