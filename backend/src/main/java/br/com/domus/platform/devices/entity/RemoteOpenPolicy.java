package br.com.domus.platform.devices.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Política do condomínio para a abertura remota pelo morador. Ausente = desligada. */
@Entity
@Table(name = "remote_open_policy")
public class RemoteOpenPolicy {

    @Id
    @Column(name = "condominium_id")
    private UUID condominiumId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_by_user_id", nullable = false)
    private UUID updatedByUserId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RemoteOpenPolicy() {
    }

    /** Construtor para criação de um(a) remote open policy. */
    public RemoteOpenPolicy(UUID condominiumId, boolean enabled, UUID updatedByUserId, Instant updatedAt) {
        this.condominiumId = condominiumId;
        this.enabled = enabled;
        this.updatedByUserId = updatedByUserId;
        this.updatedAt = updatedAt;
    }

    /**
     * Liga ou desliga a abertura remota pelo morador e registra quem alterou.
     *
     * @param enabled ligado ou desligado
     * @param userId usuário
     * @param at instante do fato
     */
    public void change(boolean enabled, UUID userId, Instant at) {
        this.enabled = enabled;
        this.updatedByUserId = userId;
        this.updatedAt = at;
    }

    /** Indica se enabled. */
    public boolean isEnabled() {
        return enabled;
    }
}
