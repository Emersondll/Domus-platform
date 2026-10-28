package br.com.domus.platform.access.entity;

import br.com.domus.platform.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Imagem da câmera de entrada de visitas. O JPEG fica em base64 num campo cifrado (AES-GCM). Sem pedido vinculado é
 * temporária; vinculada, vale a retenção da imagem.
 */
@Entity
@Table(name = "visit_snapshot")
public class VisitSnapshot {

    @Id
    private UUID id;

    @Column(name = "condominium_id", nullable = false, updatable = false)
    private UUID condominiumId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Column(name = "captured_by_user_id", nullable = false, updatable = false)
    private UUID capturedByUserId;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "visit_request_id")
    private UUID visitRequestId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, updatable = false)
    private String content;

    protected VisitSnapshot() {
    }

    /** Construtor para criação de um(a) visit snapshot. */
    public VisitSnapshot(UUID condominiumId, UUID deviceId, UUID capturedByUserId, Instant capturedAt, Instant expiresAt,
                         byte[] jpeg) {
        this.id = UUID.randomUUID();
        this.condominiumId = condominiumId;
        this.deviceId = deviceId;
        this.capturedByUserId = capturedByUserId;
        this.capturedAt = capturedAt;
        this.expiresAt = expiresAt;
        this.content = Base64.getEncoder().encodeToString(jpeg);
    }

    /**
     * Vincula o quadro capturado ao pedido de visita.
     *
     * @param visitRequestId visit request id
     */
    public void attachTo(UUID visitRequestId) {
        this.visitRequestId = visitRequestId;
    }

    /**
     * Devolve os bytes do quadro (já cifrados em repouso pela camada de persistência).
     */
    public byte[] jpeg() {
        return Base64.getDecoder().decode(content);
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o condomínio dono do registro. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o dispositivo. */
    public UUID getDeviceId() {
        return deviceId;
    }

    /** Retorna o campo captured by user id. */
    public UUID getCapturedByUserId() {
        return capturedByUserId;
    }

    /** Retorna o campo captured at. */
    public Instant getCapturedAt() {
        return capturedAt;
    }

    /** Retorna o instante de expiração. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Retorna o campo visit request id. */
    public UUID getVisitRequestId() {
        return visitRequestId;
    }
}
