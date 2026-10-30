package br.com.domus.platform.residentaccess.entity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Foto de cadastro do morador. O JPEG fica em base64 num campo cifrado (AES-GCM). Uma por morador. */
@Entity
@Table(name = "resident_photo")
public class ResidentPhoto {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "resident_id", nullable = false, updatable = false)
    private UUID residentId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String content;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "uploaded_by_user_id", nullable = false)
    private UUID uploadedByUserId;

    @Column(name = "consent_id", nullable = false)
    private UUID consentId;

    protected ResidentPhoto() {
    }

    /** Construtor para criação de um(a) resident photo. */
    public ResidentPhoto(UUID condominiumId, UUID residentId, byte[] jpeg, Instant capturedAt, UUID uploadedByUserId,
                         UUID consentId) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.residentId = residentId;
        replace(jpeg, capturedAt, uploadedByUserId, consentId);
    }

    /**
     * Substitui a imagem armazenada.
     *
     * @param jpeg imagem em JPEG
     * @param capturedAt captured at
     * @param uploadedByUserId usuário que enviou
     * @param consentId consentimento registrado
     */
    public final void replace(byte[] jpeg, Instant capturedAt, UUID uploadedByUserId, UUID consentId) {
        this.content = Base64.getEncoder().encodeToString(jpeg);
        this.capturedAt = capturedAt;
        this.uploadedByUserId = uploadedByUserId;
        this.consentId = consentId;
    }

    /**
     * Devolve os bytes da imagem.
     */
    public byte[] jpeg() {
        return Base64.getDecoder().decode(content);
    }

    /** Retorna o campo captured at. */
    public Instant getCapturedAt() {
        return capturedAt;
    }
}
