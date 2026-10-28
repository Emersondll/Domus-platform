package br.com.domus.platform.operations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Turno de um operador. Só quem tem turno aberto pode assumir chamadas. */
@Entity
@Table(name = "shift")
public class Shift {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected Shift() {
    }

    /** Construtor para criação de um(a) shift. */
    public Shift(UUID userId, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.startedAt = startedAt;
    }

    /**
     * Encerra o turno.
     *
     * @param when instante do fato
     */
    public void end(Instant when) {
        this.endedAt = when;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo started at. */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** Retorna o campo ended at. */
    public Instant getEndedAt() {
        return endedAt;
    }
}
