package br.com.domus.platform.devices.entity;

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

    @Column(name = "requested_by_resident", nullable = false, updatable = false)
    private boolean requestedByResident;

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

    /** Construtor para criação de um(a) access command. */
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

    /** Comando cuja base de autorização é o próprio morador autenticado (abertura remota habilitada). */
    public static AccessCommand byResident(UUID condominiumId, UUID deviceId, UUID residentUserId,
                                           String justification, String idempotencyKey, String correlationId,
                                           Instant requestedAt) {
        AccessCommand c = new AccessCommand(condominiumId, deviceId, residentUserId, null, null, justification,
                idempotencyKey, correlationId, requestedAt);
        c.requestedByResident = true;
        return c;
    }

    /** Indica se requested by resident. */
    public boolean isRequestedByResident() {
        return requestedByResident;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o dispositivo. */
    public UUID getDeviceId() {
        return deviceId;
    }

    /** Retorna o campo requested by. */
    public UUID getRequestedBy() {
        return requestedBy;
    }

    /** Retorna o campo call id. */
    public UUID getCallId() {
        return callId;
    }

    /** Retorna o campo attempt id. */
    public UUID getAttemptId() {
        return attemptId;
    }

    /** Retorna o campo state. */
    public String getState() {
        return state == null ? "PENDING" : state;
    }

    /** Retorna o campo failure reason. */
    public String getFailureReason() {
        return failureReason;
    }

    /** Retorna o campo evidence. */
    public String getEvidence() {
        return evidence;
    }

    /** Retorna o campo requested at. */
    public Instant getRequestedAt() {
        return requestedAt;
    }

    /** Retorna o campo sent at. */
    public Instant getSentAt() {
        return sentAt;
    }

    /** Retorna o instante da resolução. */
    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
