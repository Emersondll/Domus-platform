package br.com.domus.platform.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Notificação in-app de um morador. Push, e-mail e WhatsApp são canais futuros sobre este mesmo registro. */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    /** Construtor para criação de um(a) notification. */
    public Notification(UUID userId, UUID condominiumId, String type, String title, String body, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.condominiumId = condominiumId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
    }

    /**
     * Marca a notificação como lida.
     *
     * @param when instante do fato
     */
    public void markRead(Instant when) {
        if (readAt == null) {
            readAt = when;
        }
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo type. */
    public String getType() {
        return type;
    }

    /** Retorna o título. */
    public String getTitle() {
        return title;
    }

    /** Retorna o texto. */
    public String getBody() {
        return body;
    }

    /** Retorna o instante de criação. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Retorna o campo read at. */
    public Instant getReadAt() {
        return readAt;
    }
}
