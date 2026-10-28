package br.com.domus.platform.residents.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Animal de estimação de uma unidade. Dado do domicílio (não é dado pessoal); a portaria usa para circulação e avisos. */
@Entity
@Table(name = "pet")
public class Pet {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String species;

    private String breed;

    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Pet() {
    }

    /** Construtor para criação de um(a) pet. */
    public Pet(UUID condominiumId, UUID unitId, String name, String species, String breed, String notes,
               Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.name = name;
        this.species = species;
        this.breed = breed;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    /**
     * Atualiza os dados do animal.
     *
     * @param name nome
     * @param species espécie do animal
     * @param breed raça do animal
     * @param notes observações livres
     */
    public void update(String name, String species, String breed, String notes) {
        this.name = name;
        this.species = species;
        this.breed = breed;
        this.notes = notes;
    }

    /** Desativa o registro do animal. */
    public void deactivate() {
        this.active = false;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna a unidade. */
    public UUID getUnitId() {
        return unitId;
    }

    /** Retorna o nome. */
    public String getName() {
        return name;
    }

    /** Retorna o campo species. */
    public String getSpecies() {
        return species;
    }

    /** Retorna o campo breed. */
    public String getBreed() {
        return breed;
    }

    /** Retorna o campo notes. */
    public String getNotes() {
        return notes;
    }

    /** Indica se active. */
    public boolean isActive() {
        return active;
    }
}
