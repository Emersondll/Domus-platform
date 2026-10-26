package br.com.domus.platform.residentaccess.entity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Vínculo entre um morador e a referência que o equipamento usa para identificá-lo (cartão, usuário do terminal...). */
@Entity
@Table(name = "access_identity")
public class AccessIdentity {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "resident_id", nullable = false, updatable = false)
    private UUID residentId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "external_ref", nullable = false, updatable = false)
    private String externalRef;

    /** Índice cego (HMAC) da referência normalizada: busca e unicidade sem expor a referência. */
    @Column(name = "ref_hash", nullable = false, updatable = false)
    private String refHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AccessIdentity() {
    }

    public AccessIdentity(UUID condominiumId, UUID residentId, String kind, String externalRef, String refHash,
                          Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.residentId = residentId;
        this.kind = kind;
        this.externalRef = externalRef;
        this.refHash = refHash;
        this.createdAt = createdAt;
    }

    public void revoke(Instant when) {
        this.active = false;
        this.revokedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public UUID getResidentId() {
        return residentId;
    }

    public String getKind() {
        return kind;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
