package br.com.portaria.platform.shared.audit;

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

    public UUID getId() {
        return id;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }
}
