package br.com.domus.platform.privacy.controller;

import br.com.domus.platform.privacy.service.PrivacyService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de direitos do titular (exportação e eliminação), restrita à gestão do condomínio. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/residents/{residentId}")
public class PrivacyController {

    private final PrivacyService service;

    /** Construtor de PrivacyController; recebe as dependências por injeção. */
    public PrivacyController(PrivacyService service) {
        this.service = service;
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/residents/{residentId}/export`.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    @GetMapping("/export")
    public Map<String, Object> export(@PathVariable UUID condominiumId, @PathVariable UUID residentId) {
        return service.export(condominiumId, residentId);
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/residents/{residentId}/anonymize`.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    @PostMapping("/anonymize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymize(@PathVariable UUID condominiumId, @PathVariable UUID residentId) {
        service.anonymize(condominiumId, residentId);
    }
}
