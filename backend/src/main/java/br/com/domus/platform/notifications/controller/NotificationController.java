package br.com.domus.platform.notifications.controller;

import br.com.domus.platform.notifications.entity.Notification;
import br.com.domus.platform.notifications.entity.NotificationPreference;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API de notificações e preferências do morador. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/me")
public class NotificationController {

    private final NotificationService service;

    /** Construtor de NotificationController; recebe as dependências por injeção. */
    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * @param id        identificador
     * @param type      tipo (PARCEL_RECEIVED, VISITOR_ARRIVED, ...)
     * @param title     título
     * @param body      texto
     * @param createdAt criação
     * @param readAt    leitura, nulo se não lida
     */
    public record NotificationResponse(UUID id, String type, String title, String body, Instant createdAt,
                                       Instant readAt) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getCreatedAt(),
                    n.getReadAt());
        }
    }

    /**
     * @param parcelsEnabled         avisar sobre encomendas
     * @param visitorArrivalEnabled  avisar quando um visitante da unidade entrar
     */
    public record PreferencesRequest(boolean parcelsEnabled, boolean visitorArrivalEnabled) {
    }

    /**
     * @param parcelsEnabled         avisar sobre encomendas
     * @param visitorArrivalEnabled  avisar quando um visitante da unidade entrar
     */
    public record PreferencesResponse(boolean parcelsEnabled, boolean visitorArrivalEnabled) {
        static PreferencesResponse from(NotificationPreference p) {
            return new PreferencesResponse(p.isParcelsEnabled(), p.isVisitorArrivalEnabled());
        }
    }

    /** @param unread quantidade de notificações não lidas */
    public record UnreadResponse(long unread) {
    }

    @GetMapping("/notifications")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/notifications`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<NotificationResponse> list(@PathVariable UUID condominiumId,
            @PageableDefault(size = 30, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), NotificationResponse::from);
    }

    @GetMapping("/notifications/unread-count")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/notifications/unread-count`.
     *
     * @param condominiumId condomínio
     */
    public UnreadResponse unread(@PathVariable UUID condominiumId) {
        return new UnreadResponse(service.unreadCount(condominiumId));
    }

    @PostMapping("/notifications/{notificationId}/read")
    /**
     * Atende `POST /condominiums/{condominiumId}/me/notifications/{notificationId}/read`.
     *
     * @param condominiumId condomínio
     * @param notificationId notificação
     */
    public NotificationResponse markRead(@PathVariable UUID condominiumId, @PathVariable UUID notificationId) {
        return NotificationResponse.from(service.markRead(condominiumId, notificationId));
    }

    @GetMapping("/preferences")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/preferences`.
     *
     * @param condominiumId condomínio
     */
    public PreferencesResponse preferences(@PathVariable UUID condominiumId) {
        return PreferencesResponse.from(service.preferences(condominiumId));
    }

    @PutMapping("/preferences")
    /**
     * Atende `PUT /condominiums/{condominiumId}/me/preferences`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public PreferencesResponse updatePreferences(@PathVariable UUID condominiumId,
                                                 @Valid @RequestBody PreferencesRequest r) {
        return PreferencesResponse.from(service.updatePreferences(condominiumId, r.parcelsEnabled(),
                r.visitorArrivalEnabled()));
    }
}
