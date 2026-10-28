package br.com.domus.platform.identity.entity;

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

    /** Índice cego do e-mail (unicidade e busca por igualdade; o e-mail cifrado muda a cada gravação). */
    @Column(name = "email_hash")
    private String emailHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    /** Construtor para criação de um(a) app user. */
    public AppUser(String externalSubject, String email, String displayName) {
        this.id = UUID.randomUUID();
        this.externalSubject = externalSubject;
        this.email = email;
        this.displayName = displayName;
    }

    /** Construtor para criação de um(a) app user. */
    public AppUser(String externalSubject, String email, String displayName, String emailHash) {
        this(externalSubject, email, displayName);
        this.emailHash = emailHash;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo external subject. */
    public String getExternalSubject() {
        return externalSubject;
    }

    /** Retorna o e-mail (decifrado). */
    public String getEmail() {
        return email;
    }

    /** Retorna o nome de exibição (decifrado). */
    public String getDisplayName() {
        return displayName;
    }

    /** Indica se active. */
    public boolean isActive() {
        return active;
    }

    /** Desvincula do provedor de identidade e remove dados pessoais. */
    public void anonymize() {
        this.externalSubject = "anonymized-" + id;
        this.email = "anonymized-" + id + "@anonymized.invalid";
        this.emailHash = null;
        this.displayName = "ANONIMIZADO";
        this.active = false;
    }

    /** Retorna o campo email hash. */
    public String getEmailHash() {
        return emailHash;
    }

    /** Define o campo email hash. */
    public void setEmailHash(String emailHash) {
        this.emailHash = emailHash;
    }

    /** Desativa o usuário. */
    public void deactivate() {
        this.active = false;
    }
}
