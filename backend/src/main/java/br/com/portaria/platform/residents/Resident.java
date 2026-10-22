package br.com.portaria.platform.residents;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import br.com.portaria.platform.shared.crypto.EncryptedStringConverter;
import java.time.Instant;
import java.util.UUID;

/** Morador de uma unidade. Contém dados pessoais: acesso sempre auditado e escopado por condomínio. */
@Entity
@Table(name = "resident")
public class Resident {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Convert(converter = EncryptedStringConverter.class)
    private String email;

    @Convert(converter = EncryptedStringConverter.class)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "resident_type", nullable = false)
    private ResidentType residentType;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Resident() {
    }

    public Resident(UUID condominiumId, UUID unitId, String fullName, String email, String phone,
                    ResidentType residentType) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.unitId = unitId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.residentType = residentType;
    }

    /** Remove os dados pessoais (direito de eliminação); o vínculo estrutural com a unidade permanece. */
    public void anonymize() {
        this.fullName = "ANONIMIZADO";
        this.email = null;
        this.phone = null;
        this.active = false;
    }

    public void updateContact(String email, String phone) {
        this.email = email;
        this.phone = phone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public ResidentType getResidentType() {
        return residentType;
    }

    public boolean isActive() {
        return active;
    }
}
