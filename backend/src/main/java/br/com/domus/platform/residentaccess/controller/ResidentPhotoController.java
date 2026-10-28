package br.com.domus.platform.residentaccess.controller;

import br.com.domus.platform.residentaccess.entity.PrivacyConsent;
import br.com.domus.platform.residentaccess.service.ResidentPhotoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Foto de cadastro do próprio morador e o consentimento que a autoriza. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class ResidentPhotoController {

    private final ResidentPhotoService service;

    /** Construtor de ResidentPhotoController; recebe as dependências por injeção. */
    public ResidentPhotoController(ResidentPhotoService service) {
        this.service = service;
    }

    /**
     * @param enabled          se o recurso está ligado nesta instalação
     * @param textVersion      versão do texto de consentimento vigente
     * @param text             texto que o morador precisa ler antes de aceitar
     * @param consentId        consentimento vigente, ou {@code null} se não há
     * @param hasPhoto         se há foto cadastrada
     * @param photoCapturedAt  quando a foto foi enviada
     */
    public record PhotoStatusResponse(boolean enabled, String textVersion, String text, UUID consentId,
                                      boolean hasPhoto, Instant photoCapturedAt) {
    }

    /** @param textVersion versão do texto que o morador leu; precisa ser a vigente */
    public record GrantConsentRequest(@NotBlank String textVersion) {
    }

    /**
     * @param id          identificador do consentimento (usado para revogar)
     * @param textVersion versão do texto aceito
     * @param grantedAt   quando foi dado
     */
    public record ConsentResponse(UUID id, String textVersion, Instant grantedAt) {
        static ConsentResponse from(PrivacyConsent c) {
            return new ConsentResponse(c.getId(), c.getTextVersion(), c.getGrantedAt());
        }
    }

    /** @param hasPhoto se há foto cadastrada (a imagem nunca é exposta à portaria) */
    public record HasPhotoResponse(boolean hasPhoto) {
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/me/photo/status`.
     *
     * @param condominiumId condomínio
     */
    @GetMapping("/me/photo/status")
    public PhotoStatusResponse status(@PathVariable UUID condominiumId) {
        ResidentPhotoService.Status s = service.status(condominiumId);
        return new PhotoStatusResponse(s.enabled(), s.textVersion(), s.text(), s.consentId(), s.hasPhoto(),
                s.photoCapturedAt());
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/me/consents`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/me/consents")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentResponse grant(@PathVariable UUID condominiumId, @Valid @RequestBody GrantConsentRequest r) {
        return ConsentResponse.from(service.grant(condominiumId, r.textVersion()));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/me/consents/{consentId}/revoke`.
     *
     * @param condominiumId condomínio
     * @param consentId consent id
     */
    @PostMapping("/me/consents/{consentId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID condominiumId, @PathVariable UUID consentId) {
        service.revoke(condominiumId, consentId);
    }

    /** Envia a foto (corpo = bytes da imagem, até 2 MB); é recomprimida para no máximo 640 px e 200 KB. */
    /**
     * Atende `PUT /condominiums/{condominiumId}/me/photo`.
     *
     * @param condominiumId condomínio
     * @param image bytes da imagem enviada
     */
    @PutMapping(value = "/me/photo", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public PhotoStatusResponse upload(@PathVariable UUID condominiumId, @RequestBody byte[] image) {
        service.upload(condominiumId, image);
        return status(condominiumId);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/me/photo`.
     *
     * @param condominiumId condomínio
     */
    @GetMapping("/me/photo")
    public ResponseEntity<byte[]> photo(@PathVariable UUID condominiumId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                .body(service.view(condominiumId));
    }

    /**
     * Atende `DELETE /condominiums/{condominiumId}/me/photo`.
     *
     * @param condominiumId condomínio
     */
    @DeleteMapping("/me/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID condominiumId) {
        service.delete(condominiumId);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/residents/{residentId}/photo-status`.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    @GetMapping("/residents/{residentId}/photo-status")
    public HasPhotoResponse hasPhoto(@PathVariable UUID condominiumId, @PathVariable UUID residentId) {
        return new HasPhotoResponse(service.hasPhoto(condominiumId, residentId));
    }
}
