package br.com.domus.platform.operations.entity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Registro do livro de turno de um condomínio. O texto é livre e pode citar pessoas, por isso é cifrado. */
@Entity
@Table(name = "shift_log_entry")
public class ShiftLogEntry {

    public static final String NOTE = "NOTE";
    public static final String HANDOVER = "HANDOVER";

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "shift_id", updatable = false)
    private UUID shiftId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private UUID authorUserId;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, updatable = false)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShiftLogEntry() {
    }

    public ShiftLogEntry(UUID condominiumId, UUID shiftId, UUID authorUserId, String kind, String text,
                         Instant createdAt) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.shiftId = shiftId;
        this.authorUserId = authorUserId;
        this.kind = kind;
        this.text = text;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShiftId() {
        return shiftId;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public String getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
