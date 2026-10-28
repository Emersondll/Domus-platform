package br.com.domus.platform.reservations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Área comum reservável (salão de festas, churrasqueira, etc.). */
@Entity
@Table(name = "common_area")
public class CommonArea {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int capacity;

    protected CommonArea() {
    }

    /** Construtor para criação de um(a) common area. */
    public CommonArea(UUID condominiumId, String name, int capacity) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.name = name;
        this.capacity = capacity;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o nome. */
    public String getName() {
        return name;
    }

    /** Retorna o campo capacity. */
    public int getCapacity() {
        return capacity;
    }
}
