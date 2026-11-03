package br.com.domus.platform.notifications.controller;

import br.com.domus.platform.notifications.controller.NotificationController.NotificationResponse;
import br.com.domus.platform.notifications.controller.NotificationController.UnreadResponse;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.shared.PageResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Caixa de avisos da equipe (operador, síndico, administração) no condomínio; os moradores têm a deles em {@code /me}. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/staff/notifications")
public class StaffNotificationController {

    private final NotificationService service;

    /** Construtor de StaffNotificationController; recebe as dependências por injeção. */
    public StaffNotificationController(NotificationService service) {
        this.service = service;
    }

    /** Atende `GET /condominiums/{condominiumId}/staff/notifications`: os avisos do usuário da equipe, os mais recentes primeiro. */
    @GetMapping
    public PageResponse<NotificationResponse> list(@PathVariable UUID condominiumId,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(service.listForStaff(condominiumId, pageable), NotificationResponse::from);
    }

    /** Atende `GET /condominiums/{condominiumId}/staff/notifications/unread-count`. */
    @GetMapping("/unread-count")
    public UnreadResponse unread(@PathVariable UUID condominiumId) {
        return new UnreadResponse(service.unreadCountForStaff(condominiumId));
    }

    /** Atende `POST /condominiums/{condominiumId}/staff/notifications/{notificationId}/read`. */
    @PostMapping("/{notificationId}/read")
    public NotificationResponse markRead(@PathVariable UUID condominiumId, @PathVariable UUID notificationId) {
        return NotificationResponse.from(service.markReadForStaff(condominiumId, notificationId));
    }
}
