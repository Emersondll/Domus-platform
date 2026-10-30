package br.com.domus.platform.identity.entity;

import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Pedido de uma pessoa para ser morador do condomínio. Contém dados pessoais (cifrados) até a decisão do síndico. */
@Entity
@Table(name = "association_request")
public class AssociationRequest {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "external_subject", nullable = false, updatable = false)
    private String externalSubject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String email;

    @Convert(converter = EncryptedStringConverter.class)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_type", nullable = false)
    private ResidentType requestedType;

    @Column(name = "unit_text", nullable = false)
    private String unitText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssociationStatus status = AssociationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "resident_id")
    private UUID residentId;

    protected AssociationRequest() {
    }

    /** Construtor para criação de um(a) association request. */
    public AssociationRequest(UUID condominiumId, String externalSubject, String fullName, String email, String phone,
                              ResidentType requestedType, String unitText) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.externalSubject = externalSubject;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.requestedType = requestedType;
        this.unitText = unitText;
    }

    /** Aprova o pedido, ligando-o ao cadastro de morador criado. */
    public void approve(UUID deciderUserId, UUID residentId, Instant at) {
        this.status = AssociationStatus.APPROVED;
        this.decidedBy = deciderUserId;
        this.residentId = residentId;
        this.decidedAt = at;
    }

    /** Recusa o pedido. */
    public void reject(UUID deciderUserId, Instant at) {
        this.status = AssociationStatus.REJECTED;
        this.decidedBy = deciderUserId;
        this.decidedAt = at;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o identificador do usuário no provedor de identidade. */
    public String getExternalSubject() {
        return externalSubject;
    }

    /** Retorna o nome completo. */
    public String getFullName() {
        return fullName;
    }

    /** Retorna o e-mail. */
    public String getEmail() {
        return email;
    }

    /** Retorna o telefone. */
    public String getPhone() {
        return phone;
    }

    /** Retorna o vínculo pedido. */
    public ResidentType getRequestedType() {
        return requestedType;
    }

    /** Retorna a unidade como a pessoa a descreveu. */
    public String getUnitText() {
        return unitText;
    }

    /** Retorna a situação. */
    public AssociationStatus getStatus() {
        return status;
    }

    /** Retorna a data do pedido. */
    public Instant getCreatedAt() {
        return createdAt;
    }
}
