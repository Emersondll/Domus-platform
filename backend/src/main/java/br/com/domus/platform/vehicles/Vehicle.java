package br.com.domus.platform.vehicles;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Veículo autorizado de uma unidade. */
@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String plate;

    /** Índice cego (HMAC) da placa normalizada: permite a unicidade sem expor a placa. */
    @Column(name = "plate_hash")
    private String plateHash;

    private String model;

    private String color;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Vehicle() {
    }

    public Vehicle(UUID condominiumId, UUID unitId, String plate, String plateHash, String model, String color) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.plate = plate;
        this.plateHash = plateHash;
        this.model = model;
        this.color = color;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getPlate() {
        return plate;
    }

    public String getModel() {
        return model;
    }

    public String getColor() {
        return color;
    }
}
