package br.com.domus.platform.access.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Credencial temporária (convite com QR Code). O estado de uso é a fonte da verdade: o token apenas
 * identifica a credencial e prova autenticidade; validade, quota e revogação são lidas aqui.
 */
@Entity
@Table(name = "access_credential")
public class AccessCredential {

    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "visitor_id", updatable = false)
    private UUID visitorId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false, updatable = false)
    private Instant validUntil;

    @Column(name = "max_uses", nullable = false, updatable = false)
    private int maxUses;

    @Column(name = "use_count", nullable = false, insertable = false, updatable = false)
    private int useCount;

    @Column(nullable = false)
    private String status = ACTIVE;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AccessCredential() {
    }

    public AccessCredential(UUID condominiumId, UUID unitId, UUID visitorId, Instant validFrom, Instant validUntil,
                            int maxUses, UUID createdByUserId) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.visitorId = visitorId;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.maxUses = maxUses;
        this.createdByUserId = createdByUserId;
    }

    public void revoke(Instant when) {
        this.status = REVOKED;
        this.revokedAt = when;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getVisitorId() {
        return visitorId;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public int getUseCount() {
        return useCount;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getStatus() {
        return status;
    }
}
