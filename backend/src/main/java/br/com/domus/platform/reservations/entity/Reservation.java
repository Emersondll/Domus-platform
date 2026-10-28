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

    /** Construtor para criação de um(a) reservation. */
    public Reservation(UUID condominiumId, UUID areaId, UUID unitId, Instant startAt, Instant endAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.areaId = areaId;
        this.unitId = unitId;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    /** Cancela a reserva. */
    public void cancel() {
        this.status = CANCELLED;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo area id. */
    public UUID getAreaId() {
        return areaId;
    }

    /** Retorna a unidade. */
    public UUID getUnitId() {
        return unitId;
    }

    /** Retorna o campo start at. */
    public Instant getStartAt() {
        return startAt;
    }

    /** Retorna o campo end at. */
    public Instant getEndAt() {
        return endAt;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }
}
