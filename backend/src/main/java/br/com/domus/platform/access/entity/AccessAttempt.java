package br.com.domus.platform.access.entity;

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

    @Column(name = "visit_request_id", updatable = false)
    private UUID visitRequestId;

    @Column(name = "exited_at")
    private Instant exitedAt;

    @Column(name = "exit_registered_by_user_id")
    private UUID exitRegisteredByUserId;

    protected AccessAttempt() {
    }

    /** Construtor para criação de um(a) access attempt. */
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

    /** Entrada de visitante autorizado pelo morador na portaria (sem credencial), ligada ao pedido aprovado. */
    public static AccessAttempt walkIn(UUID condominiumId, UUID visitRequestId, String entrance, UUID actorUserId,
                                       String correlationId, Instant occurredAt) {
        AccessAttempt a = new AccessAttempt(condominiumId, null, entrance, "GRANTED", "WALK_IN_APPROVED", actorUserId,
                correlationId, occurredAt);
        a.visitRequestId = visitRequestId;
        return a;
    }

    /** Retorna o campo visit request id. */
    public UUID getVisitRequestId() {
        return visitRequestId;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo credential id. */
    public UUID getCredentialId() {
        return credentialId;
    }

    /** Retorna o ponto de acesso. */
    public String getEntrance() {
        return entrance;
    }

    /** Retorna o campo result. */
    public String getResult() {
        return result;
    }

    /** Retorna o campo reason. */
    public String getReason() {
        return reason;
    }

    /** Retorna o campo occurred at. */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** Retorna o campo exited at. */
    public Instant getExitedAt() {
        return exitedAt;
    }

    /** Registra a saída de uma entrada concedida; a regra de estado fica no serviço. */
    public void registerExit(UUID userId, Instant at) {
        this.exitedAt = at;
        this.exitRegisteredByUserId = userId;
    }
}
