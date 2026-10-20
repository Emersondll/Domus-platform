package br.com.portaria.platform.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Registro de cada tentativa de validação de credencial, concedida ou negada. */
@Entity
@Table(name = "access_attempt")
public class AccessAttempt {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "credential_id", updatable = false)
    private UUID credentialId;

    @Column(nullable = false, updatable = false)
    private String entrance;

    @Column(nullable = false, updatable = false)
    private String result;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AccessAttempt() {
    }

    public AccessAttempt(UUID condominiumId, UUID credentialId, String entrance, String result, String reason,
                         UUID actorUserId, String correlationId, Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.credentialId = credentialId;
        this.entrance = entrance;
        this.result = result;
        this.reason = reason;
        this.actorUserId = actorUserId;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCredentialId() {
        return credentialId;
    }

    public String getEntrance() {
        return entrance;
    }

    public String getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
