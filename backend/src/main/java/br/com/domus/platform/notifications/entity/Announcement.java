package br.com.domus.platform.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Comunicado da portaria ou do síndico para todos os moradores do condomínio. */
@Entity
@Table(name = "announcement")
public class Announcement {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Announcement() {
    }

    /** Construtor para criação de um(a) announcement. */
    public Announcement(UUID condominiumId, String title, String body, UUID authorUserId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.title = title;
        this.body = body;
        this.authorUserId = authorUserId;
        this.createdAt = createdAt;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
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
}
