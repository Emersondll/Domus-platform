package br.com.domus.platform.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Linha do contador de versão da política. */
@Entity
@Table(name = "condominium_policy_version")
public class PolicyVersionRow {

    @Id
    @Column(name = "condominium_id")
    private UUID condominiumId;

    @Column(nullable = false)
    private long version;

    protected PolicyVersionRow() {
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public long getVersion() {
        return version;
    }
}
