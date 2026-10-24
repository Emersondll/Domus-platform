package br.com.domus.platform.access.entity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Pedido da portaria para o morador autorizar (ou negar) a entrada de alguém sem convite prévio. */
@Entity
@Table(name = "visit_request")
public class VisitRequest {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String DENIED = "DENIED";
    /** Só existe na leitura: um pedido PENDING vencido. */
    public static final String EXPIRED = "EXPIRED";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "call_id", updatable = false)
    private UUID callId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "visitor_name", nullable = false, updatable = false)
    private String visitorName;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected VisitRequest() {
    }

    public VisitRequest(UUID condominiumId, UUID unitId, UUID callId, String visitorName, String kind,
                        UUID requestedByUserId, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.callId = callId;
        this.visitorName = visitorName;
        this.kind = kind;
        this.requestedByUserId = requestedByUserId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Estado visto por quem lê: um pedido sem resposta depois do prazo vale como expirado. */
    public String effectiveStatus(Instant now) {
        return PENDING.equals(status) && !now.isBefore(expiresAt) ? EXPIRED : status;
    }

    public void decide(boolean approve, UUID userId, Instant when) {
        this.status = approve ? APPROVED : DENIED;
        this.decidedByUserId = userId;
        this.decidedAt = when;
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

    public UUID getCallId() {
        return callId;
    }

    public String getVisitorName() {
        return visitorName;
    }

    public String getKind() {
        return kind;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
