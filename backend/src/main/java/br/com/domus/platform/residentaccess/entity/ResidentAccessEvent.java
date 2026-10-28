package br.com.domus.platform.residentaccess.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Passagem de um morador cadastrado por um ponto de acesso. Visível apenas ao próprio titular. */
@Entity
@Table(name = "resident_access_event")
public class ResidentAccessEvent {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "resident_id", nullable = false, updatable = false)
    private UUID residentId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(nullable = false, updatable = false)
    private String entrance;

    @Column(nullable = false, updatable = false)
    private String direction;

    @Column(nullable = false, updatable = false)
    private String method;

    @Column(nullable = false, updatable = false)
    private String result;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected ResidentAccessEvent() {
    }

    /** Construtor para criação de um(a) resident access event. */
    public ResidentAccessEvent(UUID condominiumId, UUID residentId, UUID deviceId, String entrance, String direction,
                               String method, String result, Instant occurredAt, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.residentId = residentId;
        this.deviceId = deviceId;
        this.entrance = entrance;
        this.direction = direction;
        this.method = method;
        this.result = result;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o ponto de acesso. */
    public String getEntrance() {
        return entrance;
    }

    /** Retorna o campo direction. */
    public String getDirection() {
        return direction;
    }

    /** Retorna o campo method. */
    public String getMethod() {
        return method;
    }

    /** Retorna o campo result. */
    public String getResult() {
        return result;
    }

    /** Retorna o campo occurred at. */
    public Instant getOccurredAt() {
        return occurredAt;
    }
}
