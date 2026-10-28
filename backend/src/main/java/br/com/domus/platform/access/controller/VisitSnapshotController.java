package br.com.domus.platform.access.controller;

import br.com.domus.platform.access.service.VisitRequestService;
import br.com.domus.platform.access.service.VisitSnapshotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Imagem da câmera de entrada de visitas: captura pela portaria e consulta pela portaria e pelo morador. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class VisitSnapshotController {

    private final VisitSnapshotService snapshots;
    private final VisitRequestService requests;

    /** Construtor de VisitSnapshotController; recebe as dependências por injeção. */
    public VisitSnapshotController(VisitSnapshotService snapshots, VisitRequestService requests) {
        this.snapshots = snapshots;
        this.requests = requests;
    }

    /** @param entrance entrada da câmera; pode faltar quando o condomínio tem uma só câmera de entrada */
    public record CaptureRequest(@Size(max = 60) String entrance) {
    }

    /**
     * @param id         identificador da imagem; envie-o em {@code snapshotId} ao criar o pedido
     * @param expiresAt  até quando a imagem pode ser enviada (depois é descartada)
     * @param cameraName câmera que gerou a imagem
     */
    public record CaptureResponse(UUID id, Instant expiresAt, String cameraName) {
    }

    /**
     * @param enabled   se a captura de imagem está ligada nesta instalação
     * @param entrances entradas com câmera de entrada de visitas ativa
     */
    public record AvailabilityResponse(boolean enabled, List<String> entrances) {
    }

    @GetMapping("/entrance-snapshots/availability")
    /**
     * Atende `GET /condominiums/{condominiumId}/entrance-snapshots/availability`.
     *
     * @param condominiumId condomínio
     */
    public AvailabilityResponse availability(@PathVariable UUID condominiumId) {
        VisitSnapshotService.Availability a = snapshots.availability(condominiumId);
        return new AvailabilityResponse(a.enabled(), a.entrances());
    }

    @PostMapping("/entrance-snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/entrance-snapshots`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public CaptureResponse capture(@PathVariable UUID condominiumId, @Valid @RequestBody CaptureRequest r) {
        VisitSnapshotService.Captured c = snapshots.capture(condominiumId, r.entrance());
        return new CaptureResponse(c.id(), c.expiresAt(), c.cameraName());
    }

    @GetMapping("/entrance-snapshots/{snapshotId}/image")
    /**
     * Atende `GET /condominiums/{condominiumId}/entrance-snapshots/{snapshotId}/image`.
     *
     * @param condominiumId condomínio
     * @param snapshotId snapshot id
     */
    public ResponseEntity<byte[]> preview(@PathVariable UUID condominiumId, @PathVariable UUID snapshotId) {
        return jpeg(snapshots.preview(condominiumId, snapshotId));
    }

    @GetMapping("/visit-requests/{requestId}/snapshot")
    /**
     * Atende `GET /condominiums/{condominiumId}/visit-requests/{requestId}/snapshot`.
     *
     * @param condominiumId condomínio
     * @param requestId pedido de visita
     */
    public ResponseEntity<byte[]> forStaff(@PathVariable UUID condominiumId, @PathVariable UUID requestId) {
        return jpeg(snapshots.imageForStaff(condominiumId, requestId));
    }

    @GetMapping("/me/visit-requests/{requestId}/snapshot")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/visit-requests/{requestId}/snapshot`.
     *
     * @param condominiumId condomínio
     * @param requestId pedido de visita
     */
    public ResponseEntity<byte[]> forResident(@PathVariable UUID condominiumId, @PathVariable UUID requestId) {
        return jpeg(requests.snapshotForResident(condominiumId, requestId));
    }

    private static ResponseEntity<byte[]> jpeg(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(bytes);
    }
}
