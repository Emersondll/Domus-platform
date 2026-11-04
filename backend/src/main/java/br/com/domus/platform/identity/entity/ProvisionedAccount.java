package br.com.domus.platform.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Conta do provedor de identidade criada pela própria API (ver V34). */
@Entity
@Table(name = "provisioned_account")
public class ProvisionedAccount {

    @Id
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ProvisionedAccount() {
    }

    /** Registra a conta criada. */
    public ProvisionedAccount(String subject) {
        this.subject = subject;
    }

    /** Retorna o {@code sub} da conta. */
    public String getSubject() {
        return subject;
    }
}
