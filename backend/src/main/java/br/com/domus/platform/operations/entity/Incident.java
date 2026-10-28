package br.com.domus.platform.operations.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Incidente operacional de um condomínio (opcionalmente originado de um atendimento). */
@Entity
@Table(name = "incident")
public class Incident {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "call_id", updatable = false)
    private UUID callId;

    @Column(nullable = false, updatable = false)
    private String severity;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(updatable = false)
    private String description;

    @Column(nullable = false)
    private String status = OPEN;

    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Incident() {
    }

    /** Construtor para criação de um(a) incident. */
    public Incident(UUID condominiumId, UUID callId, String severity, String title, String description,
                    UUID openedBy, Instant openedAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.callId = callId;
        this.severity = severity;
        this.title = title;
        this.description = description;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
    }

    /**
     * Marca a ocorrência como resolvida.
     *
     * @param when instante do fato
     */
    public void resolve(Instant when) {
        this.status = RESOLVED;
        this.resolvedAt = when;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo call id. */
    public UUID getCallId() {
        return callId;
    }

    /** Retorna o campo severity. */
    public String getSeverity() {
        return severity;
    }

    /** Retorna o título. */
    public String getTitle() {
        return title;
    }

    /** Retorna o campo description. */
    public String getDescription() {
        return description;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }

    /** Retorna o campo opened at. */
    public Instant getOpenedAt() {
        return openedAt;
    }

    /** Retorna o instante da resolução. */
    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
