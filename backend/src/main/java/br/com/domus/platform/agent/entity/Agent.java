package br.com.domus.platform.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Agente local instalado em um condomínio. Tem identidade e chave próprias, rotacionáveis e revogáveis. */
@Entity
@Table(name = "agent")
public class Agent {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false)
    private String name;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "prev_key_hash")
    private String prevKeyHash;

    @Column(name = "prev_key_expires_at")
    private Instant prevKeyExpiresAt;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_policy_version")
    private Long lastPolicyVersion;

    @Column(nullable = false)
    private int backlog;

    protected Agent() {
    }

    public Agent(UUID condominiumId, String name, String keyHash) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.name = name;
        this.keyHash = keyHash;
    }

    /** Troca a chave. A anterior continua válida por uma janela curta, até o agente confirmar a nova. */
    public void rotate(String newHash, Instant graceUntil) {
        this.prevKeyHash = this.keyHash;
        this.prevKeyExpiresAt = graceUntil;
        this.keyHash = newHash;
    }

    /** O agente já usa a nova chave: a anterior deixa de valer. */
    public void dropPreviousKey() {
        this.prevKeyHash = null;
        this.prevKeyExpiresAt = null;
    }

    public void touch(Instant when, Long policyVersion, Integer backlog) {
        this.lastSeenAt = when;
        if (policyVersion != null) {
            this.lastPolicyVersion = policyVersion;
        }
        if (backlog != null) {
            this.backlog = backlog;
        }
    }

    public void revoke() {
        this.status = "REVOKED";
    }

    public UUID getId() {
        return id;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public String getName() {
        return name;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public String getPrevKeyHash() {
        return prevKeyHash;
    }

    public Instant getPrevKeyExpiresAt() {
        return prevKeyExpiresAt;
    }

    public String getStatus() {
        return status;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Long getLastPolicyVersion() {
        return lastPolicyVersion;
    }

    public int getBacklog() {
        return backlog;
    }
}
