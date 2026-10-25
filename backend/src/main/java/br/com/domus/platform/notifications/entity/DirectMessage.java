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

    public void markRead(Instant when) {
        if (readAt == null) {
            readAt = when;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getResidentUserId() {
        return residentUserId;
    }

    public boolean isFromResident() {
        return fromResident;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
