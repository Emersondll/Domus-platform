package br.com.domus.platform.commercial.entity;

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

    /** Construtor para criação de um(a) unit. */
    public Unit(UUID condominiumId, UUID blockId, String number) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.blockId = blockId;
        this.number = number;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o campo block id. */
    public UUID getBlockId() {
        return blockId;
    }

    /** Retorna o campo number. */
    public String getNumber() {
        return number;
    }
}
