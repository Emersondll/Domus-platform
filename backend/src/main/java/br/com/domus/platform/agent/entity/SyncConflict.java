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

    public SyncConflict(UUID eventId, UUID condominiumId, String kind, String detail, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.condominiumId = condominiumId;
        this.kind = kind;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public void resolve(UUID userId, Instant when) {
        this.resolvedBy = userId;
        this.resolvedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
