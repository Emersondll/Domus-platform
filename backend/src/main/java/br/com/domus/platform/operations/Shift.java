package br.com.domus.platform.operations;

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

    public Shift(UUID userId, Instant startedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.startedAt = startedAt;
    }

    public void end(Instant when) {
        this.endedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
