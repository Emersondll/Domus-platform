package br.com.domus.platform.notifications.controller;

import br.com.domus.platform.notifications.entity.DirectMessage;
import br.com.domus.platform.notifications.service.DirectMessageService;
import br.com.domus.platform.notifications.service.DirectMessageService.ThreadSummary;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
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

/** API de mensagens individuais entre o morador e a portaria. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class DirectMessageController {

    private final DirectMessageService service;

    public DirectMessageController(DirectMessageService service) {
        this.service = service;
    }

    /** @param text texto da mensagem; não enviar senhas nem dados de pagamento */
    public record SendRequest(@NotBlank @Size(max = 1000) String text) {
    }

    /**
     * @param id          identificador da mensagem
     * @param fromResident true se foi escrita pelo morador; false se foi a portaria
     * @param text        texto
     * @param createdAt   quando foi enviada
     * @param read        se o destinatário já leu
     */
    public record MessageResponse(UUID id, boolean fromResident, String text, Instant createdAt, boolean read) {
        static MessageResponse from(DirectMessage m) {
            return new MessageResponse(m.getId(), m.isFromResident(), m.getText(), m.getCreatedAt(),
                    m.getReadAt() != null);
        }
    }

    /**
     * @param residentUserId identificador do morador (usuário) dono da conversa
     * @param residentName   nome de exibição do morador
     * @param lastAt         data da última mensagem
     * @param unread         mensagens do morador ainda não lidas pela portaria
     */
    public record ThreadResponse(UUID residentUserId, String residentName, Instant lastAt, long unread) {
        static ThreadResponse from(ThreadSummary t) {
            return new ThreadResponse(t.residentUserId(), t.residentName(), t.lastAt(), t.unread());
        }
    }

    /** @param unread respostas da portaria ainda não lidas pelo morador */
    public record UnreadResponse(long unread) {
    }

    @PostMapping("/me/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@PathVariable UUID condominiumId, @Valid @RequestBody SendRequest r) {
        return MessageResponse.from(service.sendAsResident(condominiumId, r.text()));
    }

    @GetMapping("/me/messages")
    public PageResponse<MessageResponse> listMine(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), MessageResponse::from);
    }

    @GetMapping("/me/messages/unread")
    public UnreadResponse unreadMine(@PathVariable UUID condominiumId) {
        return new UnreadResponse(service.unreadForResident(condominiumId));
    }

    @GetMapping("/messages/threads")
    public List<ThreadResponse> threads(@PathVariable UUID condominiumId) {
        return service.threads(condominiumId).stream().map(ThreadResponse::from).toList();
    }

    @GetMapping("/messages/threads/{residentUserId}")
    public PageResponse<MessageResponse> openThread(@PathVariable UUID condominiumId,
            @PathVariable UUID residentUserId,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.openThread(condominiumId, residentUserId, pageable), MessageResponse::from);
    }

    @PostMapping("/messages/threads/{residentUserId}")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse reply(@PathVariable UUID condominiumId, @PathVariable UUID residentUserId,
                                 @Valid @RequestBody SendRequest r) {
        return MessageResponse.from(service.replyAsDesk(condominiumId, residentUserId, r.text()));
    }
}
