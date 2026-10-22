package br.com.domus.platform.identity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Usuário da plataforma, vinculado ao provedor de identidade pelo claim {@code sub}. */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "external_subject", nullable = false, unique = true)
    private String externalSubject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String email;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(String externalSubject, String email, String displayName) {
        this.id = UUID.randomUUID();
        this.externalSubject = externalSubject;
        this.email = email;
        this.displayName = displayName;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isActive() {
        return active;
    }

    /** Desvincula do provedor de identidade e remove dados pessoais. */
    public void anonymize() {
        this.externalSubject = "anonymized-" + id;
        this.email = "anonymized-" + id + "@anonymized.invalid";
        this.displayName = "ANONIMIZADO";
        this.active = false;
    }

    public void deactivate() {
        this.active = false;
    }
}
