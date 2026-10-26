package br.com.domus.platform.access.controller;

import br.com.domus.platform.access.entity.VisitRequest;
import br.com.domus.platform.access.service.VisitRequestService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de autorização de visitante pelo celular. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class VisitRequestController {

    private final VisitRequestService service;

    public VisitRequestController(VisitRequestService service) {
        this.service = service;
    }

    /**
     * @param unitId      unidade visitada
     * @param visitorName nome de quem aguarda na portaria
     * @param kind        VISITOR ou SERVICE_PROVIDER
     * @param callId      atendimento em que o pedido foi feito, opcional
     */
    public record CreateVisitRequest(@NotNull UUID unitId, @NotBlank @Size(max = 160) String visitorName,
                                     @NotNull @Pattern(regexp = "VISITOR|SERVICE_PROVIDER") String kind,
                                     UUID callId) {
    }

    /**
     * @param id          identificador do pedido
     * @param unitId      unidade visitada
     * @param callId      atendimento de origem
     * @param visitorName visitante
     * @param kind        VISITOR ou SERVICE_PROVIDER
     * @param status      PENDING, APPROVED, DENIED ou EXPIRED (sem resposta no prazo)
     * @param createdAt   quando a portaria pediu
     * @param expiresAt   prazo para o morador responder
     * @param decidedAt   quando o morador respondeu
     * @param entered     se a portaria já registrou a entrada de quem foi autorizado (só informado na lista da portaria)
     */
    public record VisitRequestResponse(UUID id, UUID unitId, UUID callId, String visitorName, String kind,
                                       String status, Instant createdAt, Instant expiresAt, Instant decidedAt,
                                       boolean entered) {
        static VisitRequestResponse from(VisitRequest r) {
            return from(r, false);
        }

        static VisitRequestResponse from(VisitRequest r, boolean entered) {
            return new VisitRequestResponse(r.getId(), r.getUnitId(), r.getCallId(), r.getVisitorName(), r.getKind(),
                    r.effectiveStatus(Instant.now()), r.getCreatedAt(), r.getExpiresAt(), r.getDecidedAt(), entered);
        }
    }

    /** @param entrance entrada por onde a pessoa passou (ex.: portaria, garagem) */
    public record EntryRequest(@NotBlank @Size(max = 60) String entrance) {
    }

    /** @param attemptId registro de entrada criado; use-o para registrar a saída */
    public record EntryResponse(UUID attemptId) {
    }

    @PostMapping("/visit-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitRequestResponse create(@PathVariable UUID condominiumId, @Valid @RequestBody CreateVisitRequest r) {
        return VisitRequestResponse.from(service.request(condominiumId, r.unitId(), r.visitorName(), r.kind(),
                r.callId()));
    }

    @GetMapping("/visit-requests")
    public PageResponse<VisitRequestResponse> list(@PathVariable UUID condominiumId,
            @RequestParam(required = false) UUID callId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        org.springframework.data.domain.Page<VisitRequest> page = service.list(condominiumId, callId, pageable);
        java.util.Set<UUID> entered = service.enteredIds(condominiumId, page.getContent().stream().map(VisitRequest::getId).toList());
        return PageResponse.of(page, r -> VisitRequestResponse.from(r, entered.contains(r.getId())));
    }

    /** A portaria registra a entrada de quem o morador autorizou. Não abre porta. */
    @PostMapping("/visit-requests/{requestId}/entry")
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse entry(@PathVariable UUID condominiumId, @PathVariable UUID requestId,
                               @Valid @RequestBody EntryRequest r) {
        return new EntryResponse(service.registerEntry(condominiumId, requestId, r.entrance().trim()).getId());
    }

    @GetMapping("/me/visit-requests")
    public PageResponse<VisitRequestResponse> listMine(@PathVariable UUID condominiumId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), VisitRequestResponse::from);
    }

    @PostMapping("/me/visit-requests/{requestId}/approve")
    public VisitRequestResponse approve(@PathVariable UUID condominiumId, @PathVariable UUID requestId) {
        return VisitRequestResponse.from(service.decide(condominiumId, requestId, true));
    }

    @PostMapping("/me/visit-requests/{requestId}/deny")
    public VisitRequestResponse deny(@PathVariable UUID condominiumId, @PathVariable UUID requestId) {
        return VisitRequestResponse.from(service.decide(condominiumId, requestId, false));
    }
}
