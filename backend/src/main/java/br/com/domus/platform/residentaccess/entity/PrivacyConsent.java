package br.com.domus.platform.residentaccess.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Consentimento específico do morador para uma finalidade, com a versão do texto que ele viu. Revogável. */
@Entity
@Table(name = "privacy_consent")
public class PrivacyConsent {

    public static final String ACCESS_PHOTO = "ACCESS_PHOTO";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "resident_id", nullable = false, updatable = false)
    private UUID residentId;

    @Column(nullable = false, updatable = false)
    private String purpose;

    @Column(name = "text_version", nullable = false, updatable = false)
    private String textVersion;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "granted_by_user_id", nullable = false, updatable = false)
    private UUID grantedByUserId;

    protected PrivacyConsent() {
    }

    /** Construtor para criação de um(a) privacy consent. */
    public PrivacyConsent(UUID condominiumId, UUID residentId, String purpose, String textVersion, Instant grantedAt,
                          UUID grantedByUserId) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.residentId = residentId;
        this.purpose = purpose;
        this.textVersion = textVersion;
        this.grantedAt = grantedAt;
        this.grantedByUserId = grantedByUserId;
    }

    /**
     * Revoga o consentimento.
     *
     * @param when instante do fato
     */
    public void revoke(Instant when) {
        if (revokedAt == null) {
            this.revokedAt = when;
        }
    }

    /** Indica se active. */
    public boolean isActive() {
        return revokedAt == null;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o morador. */
    public UUID getResidentId() {
        return residentId;
    }

    /** Retorna o campo text version. */
    public String getTextVersion() {
        return textVersion;
    }

    /** Retorna o campo granted at. */
    public Instant getGrantedAt() {
        return grantedAt;
    }

    /** Retorna o campo revoked at. */
    public Instant getRevokedAt() {
        return revokedAt;
    }
}
