package br.com.domus.platform.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Comando físico de abertura. Estados: PENDING (registrado), SENT (equipamento aceitou), CONFIRMED (evidência de
 * abertura), FAILED (recusado/offline) e UNKNOWN (sem confirmação: não se presume que a porta abriu nem que não abriu).
 * As transições são feitas por UPDATEs condicionais no repositório.
 */
@Entity
@Table(name = "access_command")
public class AccessCommand {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "call_id", updatable = false)
    private UUID callId;

    @Column(name = "attempt_id", updatable = false)
    private UUID attemptId;

    @Column(nullable = false, updatable = false)
    private String justification;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, insertable = false, updatable = false)
    private String state;

    @Column(name = "failure_reason", insertable = false, updatable = false)
    private String failureReason;

    @Column(insertable = false, updatable = false)
    private String evidence;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "sent_at", insertable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "resolved_at", insertable = false, updatable = false)
    private Instant resolvedAt;

    protected AccessCommand() {
    }

    public AccessCommand(UUID condominiumId, UUID deviceId, UUID requestedBy, UUID callId, UUID attemptId,
                         String justification, String idempotencyKey, String correlationId, Instant requestedAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.deviceId = deviceId;
        this.requestedBy = requestedBy;
        this.callId = callId;
        this.attemptId = attemptId;
        this.justification = justification;
        this.idempotencyKey = idempotencyKey;
        this.correlationId = correlationId;
        this.requestedAt = requestedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public UUID getCallId() {
        return callId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getState() {
        return state == null ? "PENDING" : state;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getEvidence() {
        return evidence;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
