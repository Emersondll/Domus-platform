package br.com.portaria.platform.commercial;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Unidade (apartamento, casa ou sala) pertencente a um bloco. */
@Entity
@Table(name = "unit")
public class Unit {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "block_id", nullable = false, updatable = false)
    private UUID blockId;

    @Column(nullable = false)
    private String number;

    protected Unit() {
    }

    public Unit(UUID condominiumId, UUID blockId, String number) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.blockId = blockId;
        this.number = number;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public UUID getBlockId() {
        return blockId;
    }

    public String getNumber() {
        return number;
    }
}
