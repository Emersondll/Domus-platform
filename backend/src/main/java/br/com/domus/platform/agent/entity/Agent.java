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

    /** Construtor para criação de um(a) agent. */
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

    /**
     * Atualiza o último contato, a política confirmada e o backlog informados pelo agente.
     *
     * @param when instante do fato
     * @param policyVersion versão de política
     * @param backlog eventos pendentes no agente
     */
    public void touch(Instant when, Long policyVersion, Integer backlog) {
        this.lastSeenAt = when;
        if (policyVersion != null) {
            this.lastPolicyVersion = policyVersion;
        }
        if (backlog != null) {
            this.backlog = backlog;
        }
    }

    /** Marca o agente como revogado. */
    public void revoke() {
        this.status = "REVOKED";
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o nome. */
    public String getName() {
        return name;
    }

    /** Retorna o campo key hash. */
    public String getKeyHash() {
        return keyHash;
    }

    /** Retorna o campo prev key hash. */
    public String getPrevKeyHash() {
        return prevKeyHash;
    }

    /** Retorna o campo prev key expires at. */
    public Instant getPrevKeyExpiresAt() {
        return prevKeyExpiresAt;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }

    /** Retorna o campo last seen at. */
    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    /** Retorna o campo last policy version. */
    public Long getLastPolicyVersion() {
        return lastPolicyVersion;
    }

    /** Retorna o campo backlog. */
    public int getBacklog() {
        return backlog;
    }
}
