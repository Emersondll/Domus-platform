package br.com.domus.platform.notifications.controller;

import br.com.domus.platform.notifications.entity.Announcement;
import br.com.domus.platform.notifications.service.AnnouncementService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de comunicados do condomínio. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class AnnouncementController {

    private final AnnouncementService service;

    /** Construtor de AnnouncementController; recebe as dependências por injeção. */
    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    /**
     * @param title título curto do comunicado
     * @param body  texto do comunicado
     */
    public record PublishRequest(@NotBlank @Size(max = 120) String title, @NotBlank @Size(max = 2000) String body) {
    }

    /**
     * @param id        identificador do comunicado
     * @param title     título
     * @param body      texto
     * @param createdAt quando foi publicado
     */
    public record AnnouncementResponse(UUID id, String title, String body, Instant createdAt) {
        static AnnouncementResponse from(Announcement a) {
            return new AnnouncementResponse(a.getId(), a.getTitle(), a.getBody(), a.getCreatedAt());
        }
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/announcements`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementResponse publish(@PathVariable UUID condominiumId, @Valid @RequestBody PublishRequest r) {
        return AnnouncementResponse.from(service.publish(condominiumId, r.title(), r.body()));
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/announcements`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/announcements")
    public PageResponse<AnnouncementResponse> list(@PathVariable UUID condominiumId,
            @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, pageable), AnnouncementResponse::from);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/me/announcements`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/me/announcements")
    public PageResponse<AnnouncementResponse> listMine(@PathVariable UUID condominiumId,
            @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), AnnouncementResponse::from);
    }
}
