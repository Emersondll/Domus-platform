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

    /** Construtor para criação de um(a) visit request. */
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

    /**
     * Registra a decisão do morador sobre o pedido de visita.
     *
     * @param approve verdadeiro para autorizar, falso para negar
     * @param userId usuário
     * @param when instante do fato
     */
    public void decide(boolean approve, UUID userId, Instant when) {
        this.status = approve ? APPROVED : DENIED;
        this.decidedByUserId = userId;
        this.decidedAt = when;
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

    /** Retorna o campo call id. */
    public UUID getCallId() {
        return callId;
    }

    /** Retorna o campo visitor name. */
    public String getVisitorName() {
        return visitorName;
    }

    /** Retorna o tipo. */
    public String getKind() {
        return kind;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }

    /** Retorna o instante de criação. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Retorna o instante de expiração. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Retorna o campo decided at. */
    public Instant getDecidedAt() {
        return decidedAt;
    }
}
