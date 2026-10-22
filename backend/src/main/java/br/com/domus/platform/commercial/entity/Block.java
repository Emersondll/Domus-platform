package br.com.domus.platform.commercial.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Bloco/torre de um condomínio. */
@Entity
@Table(name = "block")
public class Block {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false)
    private String name;

    protected Block() {
    }

    public Block(UUID condominiumId, String name) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.name = name;
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
}
