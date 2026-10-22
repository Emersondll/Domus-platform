package br.com.domus.platform.operations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Registro de uma decisão ou movimentação em um atendimento. */
@Entity
@Table(name = "call_event")
public class CallEvent {

    @Id
    private UUID id;

    @Column(name = "call_id", nullable = false, updatable = false)
    private UUID callId;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected CallEvent() {
    }

    public CallEvent(UUID callId, UUID condominiumId, UUID actorUserId, String type, String detail,
                     Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.callId = callId;
        this.condominiumId = condominiumId;
        this.actorUserId = actorUserId;
        this.type = type;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getType() {
        return type;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
