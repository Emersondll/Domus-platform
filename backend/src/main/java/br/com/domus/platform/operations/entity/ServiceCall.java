package br.com.domus.platform.operations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;

/** Atendimento da central: entra na fila, é assumido por um operador e encerrado com um desfecho. */
@Entity
@Table(name = "service_call")
public class ServiceCall {

    public static final String QUEUED = "QUEUED";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String CLOSED = "CLOSED";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", updatable = false)
    private UUID unitId;

    @Column(nullable = false, updatable = false)
    private String source;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "caller_name", updatable = false)
    private String callerName;

    @Column(nullable = false)
    private String status = QUEUED;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    private String outcome;

    protected ServiceCall() {
    }

    public ServiceCall(UUID condominiumId, UUID unitId, String source, String callerName, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.source = source;
        this.callerName = callerName;
        this.createdAt = createdAt;
    }

    public void transferTo(UUID userId, Instant when) {
        this.assignedUserId = userId;
        this.assignedAt = when;
    }

    public void close(String outcome, Instant when) {
        this.status = CLOSED;
        this.outcome = outcome;
        this.closedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getSource() {
        return source;
    }

    public String getCallerName() {
        return callerName;
    }

    public String getStatus() {
        return status;
    }

    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getOutcome() {
        return outcome;
    }
}
