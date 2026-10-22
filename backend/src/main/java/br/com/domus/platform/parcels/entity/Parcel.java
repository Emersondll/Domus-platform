package br.com.domus.platform.parcels.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Encomenda recebida na portaria para uma unidade. */
@Entity
@Table(name = "parcel")
public class Parcel {

    public static final String RECEIVED = "RECEIVED";
    public static final String PICKED_UP = "PICKED_UP";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    private String carrier;

    private String description;

    @Column(nullable = false)
    private String status = RECEIVED;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "received_by_user_id", nullable = false, updatable = false)
    private UUID receivedByUserId;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "picked_up_by_name")
    private String pickedUpByName;

    protected Parcel() {
    }

    public Parcel(UUID condominiumId, UUID unitId, String carrier, String description, UUID receivedByUserId,
                  Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.carrier = carrier;
        this.description = description;
        this.receivedByUserId = receivedByUserId;
        this.receivedAt = receivedAt;
    }

    public void pickUp(String personName, Instant when) {
        this.status = PICKED_UP;
        this.pickedUpByName = personName;
        this.pickedUpAt = when;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getPickedUpAt() {
        return pickedUpAt;
    }

    public String getPickedUpByName() {
        return pickedUpByName;
    }
}
