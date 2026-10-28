package br.com.domus.platform.residents.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
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

    /** Construtor para criação de um(a) resident. */
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

    /** Atualização cadastral feita pela administração do condomínio. */
    public void update(String fullName, String email, String phone, ResidentType type, boolean active) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.residentType = type;
        this.active = active;
    }

    /**
     * Atualiza os dados de contato do morador.
     *
     * @param email e-mail
     * @param phone telefone de contato
     */
    public void updateContact(String email, String phone) {
        this.email = email;
        this.phone = phone;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna a unidade. */
    public UUID getUnitId() {
        return unitId;
    }

    /** Retorna o nome completo (decifrado). */
    public String getFullName() {
        return fullName;
    }

    /** Retorna o e-mail (decifrado). */
    public String getEmail() {
        return email;
    }

    /** Retorna o campo phone. */
    public String getPhone() {
        return phone;
    }

    /** Retorna o campo resident type. */
    public ResidentType getResidentType() {
        return residentType;
    }

    /** Indica se active. */
    public boolean isActive() {
        return active;
    }
}
