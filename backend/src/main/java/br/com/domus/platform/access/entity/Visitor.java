package br.com.domus.platform.access.entity;

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

    /** Construtor para criação de um(a) visitor. */
    public Visitor(UUID condominiumId, UUID unitId, String fullName, String kind) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.fullName = fullName;
        this.kind = kind;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna a unidade. */
    public UUID getUnitId() {
        return unitId;
    }

    /** Retorna o nome completo (decifrado). */
    public String getFullName() {
        return fullName;
    }

    /** Retorna o tipo. */
    public String getKind() {
        return kind;
    }
}
