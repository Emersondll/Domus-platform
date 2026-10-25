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

    public void update(String name, String species, String breed, String notes) {
        this.name = name;
        this.species = species;
        this.breed = breed;
        this.notes = notes;
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getName() {
        return name;
    }

    public String getSpecies() {
        return species;
    }

    public String getBreed() {
        return breed;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isActive() {
        return active;
    }
}
