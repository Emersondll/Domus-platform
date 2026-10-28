package br.com.domus.platform.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Registro imutável de auditoria (append-only também no banco, via trigger). */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "operator_company_id", updatable = false)
    private UUID operatorCompanyId;

    @Column(name = "condominium_id", updatable = false)
    private UUID condominiumId;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "resource_type", updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private String resourceId;

    @Column(nullable = false, updatable = false)
    private String outcome;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    protected AuditLogEntry() {
    }

    AuditLogEntry(UUID actorUserId, UUID operatorCompanyId, UUID condominiumId, String action,
                  String resourceType, String resourceId, AuditOutcome outcome, String correlationId) {
        this.id = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.actorUserId = actorUserId;
        this.operatorCompanyId = operatorCompanyId;
        this.condominiumId = condominiumId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = outcome.name();
        this.correlationId = correlationId;
    }

    /**
     * Retorna o identificador do registro.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Retorna o usuário que praticou a ação, se houver.
     */
    public UUID getActorUserId() {
        return actorUserId;
    }

    /**
     * Retorna o condomínio da ação, se houver.
     */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /**
     * Retorna o código da ação auditada.
     *
     * @return texto resultante
     */
    public String getAction() {
        return action;
    }

    /**
     * Retorna o desfecho (sucesso, negado ou falha).
     *
     * @return texto resultante
     */
    public String getOutcome() {
        return outcome;
    }
}
