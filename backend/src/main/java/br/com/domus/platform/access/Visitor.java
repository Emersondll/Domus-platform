package br.com.domus.platform.access;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;

/** Visitante ou prestador de serviço convidado por uma unidade. */
@Entity
@Table(name = "visitor")
public class Visitor {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Visitor() {
    }

    public Visitor(UUID condominiumId, UUID unitId, String fullName, String kind) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.fullName = fullName;
        this.kind = kind;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getKind() {
        return kind;
    }
}
