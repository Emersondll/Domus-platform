package br.com.domus.platform.notifications.entity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Mensagem individual entre um morador e a portaria. O texto é livre e pode citar pessoas, por isso é cifrado. */
@Entity
@Table(name = "direct_message")
public class DirectMessage {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "resident_user_id", nullable = false, updatable = false)
    private UUID residentUserId;

    @Column(name = "sender_user_id", nullable = false, updatable = false)
    private UUID senderUserId;

    @Column(name = "from_resident", nullable = false, updatable = false)
    private boolean fromResident;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, updatable = false)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected DirectMessage() {
    }

    /** Construtor para criação de um(a) direct message. */
    public DirectMessage(UUID condominiumId, UUID residentUserId, UUID senderUserId, boolean fromResident,
                         String text, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.residentUserId = residentUserId;
        this.senderUserId = senderUserId;
        this.fromResident = fromResident;
        this.text = text;
        this.createdAt = createdAt;
    }

    /**
     * Marca a mensagem como lida.
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

    /** Retorna o campo resident user id. */
    public UUID getResidentUserId() {
        return residentUserId;
    }

    /** Indica se from resident. */
    public boolean isFromResident() {
        return fromResident;
    }

    /** Retorna o campo text. */
    public String getText() {
        return text;
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
