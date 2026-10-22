package br.com.domus.platform.reservations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Reserva de uma área comum por uma unidade. */
@Entity
@Table(name = "reservation")
public class Reservation {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "area_id", nullable = false, updatable = false)
    private UUID areaId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false, updatable = false)
    private Instant endAt;

    @Column(nullable = false)
    private String status = CONFIRMED;

    protected Reservation() {
    }

    public Reservation(UUID condominiumId, UUID areaId, UUID unitId, Instant startAt, Instant endAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.areaId = areaId;
        this.unitId = unitId;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public void cancel() {
        this.status = CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAreaId() {
        return areaId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public String getStatus() {
        return status;
    }
}
