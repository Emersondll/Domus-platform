package br.com.portaria.platform.operations;

import br.com.portaria.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API da central de operações. Sem regra de negócio: delega ao {@link OperationsService}. */
@RestController
@RequestMapping("/api/v1")
public class OperationsController {

    private final OperationsService service;

    public OperationsController(OperationsService service) {
        this.service = service;
    }

    /**
     * @param id        identificador do turno
     * @param startedAt início do turno
     * @param endedAt   fim do turno, nulo se aberto
     */
    public record ShiftResponse(UUID id, Instant startedAt, Instant endedAt) {
        static ShiftResponse from(Shift s) {
            return new ShiftResponse(s.getId(), s.getStartedAt(), s.getEndedAt());
        }
    }

    /**
     * @param unitId     unidade relacionada, opcional
     * @param callerName nome de quem chamou, opcional
     */
    public record CreateCallRequest(UUID unitId, @Size(max = 160) String callerName) {
    }

    /** @param callerName nome informado pelo morador, opcional */
    public record ResidentCallRequest(@Size(max = 160) String callerName) {
    }

    /**
     * @param toUserId usuário operador que receberá o atendimento
     * @param reason   motivo da transferência
     */
    public record TransferRequest(@NotNull UUID toUserId, @NotBlank @Size(max = 300) String reason) {
    }

    /**
     * @param outcome       RESOLVED, ENTRY_GRANTED, ENTRY_DENIED, NO_ANSWER ou FALSE_ALARM
     * @param justification obrigatória (mín. 5 caracteres) para ENTRY_GRANTED e ENTRY_DENIED
     */
    public record CloseRequest(@NotBlank String outcome, @Size(max = 400) String justification) {
    }

    /**
     * @param id             identificador do atendimento
     * @param condominiumId  condomínio
     * @param unitId         unidade relacionada
     * @param source         MANUAL, RESIDENT_APP ou INTERCOM
     * @param callerName     nome de quem chamou
     * @param status         QUEUED, ASSIGNED ou CLOSED
     * @param assignedUserId operador responsável
     * @param createdAt      entrada na fila
     * @param closedAt       encerramento
     * @param outcome        desfecho
     */
    public record CallResponse(UUID id, UUID condominiumId, UUID unitId, String source, String callerName,
                               String status, UUID assignedUserId, Instant createdAt, Instant closedAt,
                               String outcome) {
        static CallResponse from(ServiceCall c) {
            return new CallResponse(c.getId(), c.getCondominiumId(), c.getUnitId(), c.getSource(),
                    c.getCallerName(), c.getStatus(), c.getAssignedUserId(), c.getCreatedAt(), c.getClosedAt(),
                    c.getOutcome());
        }
    }

    /**
     * @param type       tipo do evento
     * @param detail     detalhe/justificativa
     * @param actorUserId autor
     * @param occurredAt instante
     */
    public record CallEventResponse(String type, String detail, UUID actorUserId, Instant occurredAt) {
        static CallEventResponse from(CallEvent e) {
            return new CallEventResponse(e.getType(), e.getDetail(), e.getActorUserId(), e.getOccurredAt());
        }
    }

    /**
     * @param callId      atendimento de origem, opcional
     * @param severity    LOW, MEDIUM ou HIGH
     * @param title       título
     * @param description descrição, opcional
     */
    public record OpenIncidentRequest(UUID callId, @NotNull @Pattern(regexp = "LOW|MEDIUM|HIGH") String severity,
                                      @NotBlank @Size(max = 160) String title, @Size(max = 2000) String description) {
    }

    /**
     * @param id          identificador
     * @param callId      atendimento de origem
     * @param severity    gravidade
     * @param title       título
     * @param description descrição
     * @param status      OPEN ou RESOLVED
     * @param openedAt    abertura
     * @param resolvedAt  resolução
     */
    public record IncidentResponse(UUID id, UUID callId, String severity, String title, String description,
                                   String status, Instant openedAt, Instant resolvedAt) {
        static IncidentResponse from(Incident i) {
            return new IncidentResponse(i.getId(), i.getCallId(), i.getSeverity(), i.getTitle(),
                    i.getDescription(), i.getStatus(), i.getOpenedAt(), i.getResolvedAt());
        }
    }

    /**
     * @param condominiumId    condomínio
     * @param name             nome
     * @param queuedCalls      atendimentos na fila
     * @param assignedCalls    atendimentos em andamento
     * @param openIncidents    incidentes abertos
     * @param deviceHealth     saúde dos dispositivos: NOT_IMPLEMENTED até a Fase 6
     * @param connectivity     conectividade/contingência: NOT_IMPLEMENTED até a Fase 7
     */
    public record OverviewResponse(UUID condominiumId, String name, long queuedCalls, long assignedCalls,
                                   long openIncidents, String deviceHealth, String connectivity) {
    }

    @PostMapping("/operator/shifts/start")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftResponse startShift() {
        return ShiftResponse.from(service.startShift());
    }

    @PostMapping("/operator/shifts/end")
    public ShiftResponse endShift() {
        return ShiftResponse.from(service.endShift());
    }

    @GetMapping("/operator/shifts/current")
    public ResponseEntity<ShiftResponse> currentShift() {
        return service.currentShift().map(s -> ResponseEntity.ok(ShiftResponse.from(s)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/operator/calls/next")
    public ResponseEntity<CallResponse> claimNext() {
        return service.claimNext().map(c -> ResponseEntity.ok(CallResponse.from(c)))
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/operator/calls/mine")
    public PageResponse<CallResponse> myCalls(@PageableDefault(size = 50, sort = "assignedAt") Pageable pageable) {
        return PageResponse.of(service.myActiveCalls(pageable), CallResponse::from);
    }

    @GetMapping("/operator/overview")
    public List<OverviewResponse> overview() {
        return service.overview().stream().map(o -> new OverviewResponse(o.condominium().getId(),
                o.condominium().getName(), o.queuedCalls(), o.assignedCalls(), o.openIncidents(),
                "NOT_IMPLEMENTED", "NOT_IMPLEMENTED")).toList();
    }

    @PostMapping("/condominiums/{condominiumId}/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallResponse createCall(@PathVariable UUID condominiumId, @Valid @RequestBody CreateCallRequest r) {
        return CallResponse.from(service.createCall(condominiumId, r.unitId(), r.callerName()));
    }

    @PostMapping("/condominiums/{condominiumId}/me/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallResponse createResidentCall(@PathVariable UUID condominiumId,
                                           @Valid @RequestBody ResidentCallRequest r) {
        return CallResponse.from(service.createCallAsResident(condominiumId, r.callerName()));
    }

    @GetMapping("/condominiums/{condominiumId}/calls")
    public PageResponse<CallResponse> listCalls(@PathVariable UUID condominiumId,
                                                @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(service.listCalls(condominiumId, pageable), CallResponse::from);
    }

    @PostMapping("/condominiums/{condominiumId}/calls/{callId}/transfer")
    public CallResponse transfer(@PathVariable UUID condominiumId, @PathVariable UUID callId,
                                 @Valid @RequestBody TransferRequest r) {
        return CallResponse.from(service.transfer(condominiumId, callId, r.toUserId(), r.reason()));
    }

    @PostMapping("/condominiums/{condominiumId}/calls/{callId}/close")
    public CallResponse close(@PathVariable UUID condominiumId, @PathVariable UUID callId,
                              @Valid @RequestBody CloseRequest r) {
        return CallResponse.from(service.close(condominiumId, callId, r.outcome(), r.justification()));
    }

    @GetMapping("/condominiums/{condominiumId}/calls/{callId}/events")
    public List<CallEventResponse> events(@PathVariable UUID condominiumId, @PathVariable UUID callId) {
        return service.callEvents(condominiumId, callId).stream().map(CallEventResponse::from).toList();
    }

    /**
     * @param id          identificador do usuário operador
     * @param displayName nome de exibição
     */
    public record OnDutyOperatorResponse(UUID id, String displayName) {
    }

    @GetMapping("/condominiums/{condominiumId}/operators/on-duty")
    public List<OnDutyOperatorResponse> onDuty(@PathVariable UUID condominiumId) {
        return service.onDutyOperators(condominiumId).stream()
                .map(o -> new OnDutyOperatorResponse(o.getId(), o.getDisplayName())).toList();
    }

    @PostMapping("/condominiums/{condominiumId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentResponse openIncident(@PathVariable UUID condominiumId,
                                         @Valid @RequestBody OpenIncidentRequest r) {
        return IncidentResponse.from(service.openIncident(condominiumId, r.callId(), r.severity(), r.title(),
                r.description()));
    }

    @PostMapping("/condominiums/{condominiumId}/incidents/{incidentId}/resolve")
    public IncidentResponse resolveIncident(@PathVariable UUID condominiumId, @PathVariable UUID incidentId) {
        return IncidentResponse.from(service.resolveIncident(condominiumId, incidentId));
    }

    @GetMapping("/condominiums/{condominiumId}/incidents")
    public PageResponse<IncidentResponse> incidents(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "openedAt") Pageable pageable) {
        return PageResponse.of(service.listIncidents(condominiumId, pageable), IncidentResponse::from);
    }
}
