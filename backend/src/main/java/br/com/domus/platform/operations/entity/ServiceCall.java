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

    /** Construtor para criação de um(a) service call. */
    public ServiceCall(UUID condominiumId, UUID unitId, String source, String callerName, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.source = source;
        this.callerName = callerName;
        this.createdAt = createdAt;
    }

    /**
     * Transfere o atendimento para outro operador.
     *
     * @param userId usuário
     * @param when instante do fato
     */
    public void transferTo(UUID userId, Instant when) {
        this.assignedUserId = userId;
        this.assignedAt = when;
    }

    /**
     * Encerra o atendimento com o desfecho informado.
     *
     * @param outcome desfecho
     * @param when instante do fato
     */
    public void close(String outcome, Instant when) {
        this.status = CLOSED;
        this.outcome = outcome;
        this.closedAt = when;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna a unidade. */
    public UUID getUnitId() {
        return unitId;
    }

    /** Retorna o campo source. */
    public String getSource() {
        return source;
    }

    /** Retorna o campo caller name. */
    public String getCallerName() {
        return callerName;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }

    /** Retorna o campo assigned user id. */
    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    /** Retorna o instante de criação. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Retorna o campo assigned at. */
    public Instant getAssignedAt() {
        return assignedAt;
    }

    /** Retorna o campo closed at. */
    public Instant getClosedAt() {
        return closedAt;
    }

    /** Retorna o campo outcome. */
    public String getOutcome() {
        return outcome;
    }
}
