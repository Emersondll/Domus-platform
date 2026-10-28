package br.com.domus.platform.operations.controller;

import br.com.domus.platform.operations.entity.CallEvent;
import br.com.domus.platform.operations.entity.Incident;
import br.com.domus.platform.operations.entity.ServiceCall;
import br.com.domus.platform.operations.entity.Shift;
import br.com.domus.platform.operations.service.OperationsService;
import br.com.domus.platform.shared.PageResponse;
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

    /** Construtor de OperationsController; recebe as dependências por injeção. */
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
     * @param unitLabel      "Bloco / número" da unidade, quando há (alertas de pânico sempre têm)
     */
    public record CallResponse(UUID id, UUID condominiumId, UUID unitId, String source, String callerName,
                               String status, UUID assignedUserId, Instant createdAt, Instant closedAt,
                               String outcome, String unitLabel) {
        static CallResponse from(ServiceCall c, String unitLabel) {
            return new CallResponse(c.getId(), c.getCondominiumId(), c.getUnitId(), c.getSource(),
                    c.getCallerName(), c.getStatus(), c.getAssignedUserId(), c.getCreatedAt(), c.getClosedAt(),
                    c.getOutcome(), unitLabel);
        }
    }

    /**
     * @param callId          atendimento do alerta
     * @param condominiumId   condomínio
     * @param condominiumName nome do condomínio
     * @param unitId          unidade que acionou
     * @param unitLabel       "Bloco / número" da unidade que acionou
     * @param createdAt       quando foi acionado
     */
    public record PanicAlertResponse(UUID callId, UUID condominiumId, String condominiumName, UUID unitId,
                                     String unitLabel, Instant createdAt) {
    }

    private CallResponse view(ServiceCall c) {
        return CallResponse.from(c, service.unitLabel(c));
    }

    /**
     * Visão da chamada para o morador: sem operador responsável nem justificativa.
     *
     * @param id         identificador da chamada
     * @param source     RESIDENT_APP (chamada) ou PANIC (alerta de pânico)
     * @param status     QUEUED (aguardando), ASSIGNED (em atendimento) ou CLOSED (encerrada)
     * @param createdAt  quando o pedido foi feito
     * @param assignedAt quando um operador assumiu
     * @param closedAt   quando foi encerrada
     * @param outcome    desfecho (RESOLVED, ENTRY_GRANTED, ENTRY_DENIED, NO_ANSWER, FALSE_ALARM)
     */
    public record MyCallResponse(UUID id, String source, String status, Instant createdAt, Instant assignedAt, Instant closedAt,
                                 String outcome) {
        static MyCallResponse from(ServiceCall c) {
            return new MyCallResponse(c.getId(), c.getSource(), c.getStatus(), c.getCreatedAt(), c.getAssignedAt(), c.getClosedAt(),
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
     * @param queuedPanics     alertas de pânico aguardando atendimento (têm prioridade na fila)
     * @param deviceHealth     resumo da saúde dos dispositivos (NO_DEVICES, ONLINE n/n, DEGRADED x/n, UNKNOWN n/n)
     * @param connectivity     conectividade do agente local: NO_AGENT, ONLINE ou OFFLINE (com eventos pendentes)
     */
    public record OverviewResponse(UUID condominiumId, String name, long queuedCalls, long assignedCalls,
                                   long openIncidents, long queuedPanics, String deviceHealth,
                                   String connectivity) {
    }

    @PostMapping("/operator/shifts/start")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /operator/shifts/start`.
     */
    public ShiftResponse startShift() {
        return ShiftResponse.from(service.startShift());
    }

    @PostMapping("/operator/shifts/end")
    /**
     * Atende `POST /operator/shifts/end`.
     */
    public ShiftResponse endShift() {
        return ShiftResponse.from(service.endShift());
    }

    @GetMapping("/operator/shifts/current")
    /**
     * Atende `GET /operator/shifts/current`.
     */
    public ResponseEntity<ShiftResponse> currentShift() {
        return service.currentShift().map(s -> ResponseEntity.ok(ShiftResponse.from(s)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/operator/calls/next")
    /**
     * Atende `POST /operator/calls/next`.
     */
    public ResponseEntity<CallResponse> claimNext() {
        return service.claimNext().map(c -> ResponseEntity.ok(view(c)))
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/operator/calls/mine")
    /**
     * Atende `GET /operator/calls/mine`.
     *
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<CallResponse> myCalls(@PageableDefault(size = 50, sort = "assignedAt") Pageable pageable) {
        return PageResponse.of(service.myActiveCalls(pageable), this::view);
    }

    /** Alertas de pânico aguardando atendimento (a portaria consulta com frequência para alertar na tela). */
    @GetMapping("/operator/panics")
    public List<PanicAlertResponse> panics() {
        return service.queuedPanicAlerts().stream().map(p -> new PanicAlertResponse(p.callId(), p.condominiumId(),
                p.condominiumName(), p.unitId(), p.unitLabel(), p.createdAt())).toList();
    }

    @GetMapping("/operator/overview")
    /**
     * Atende `GET /operator/overview`.
     *
     * @return lista de resultados
     */
    public List<OverviewResponse> overview() {
        return service.overview().stream().map(o -> new OverviewResponse(o.condominium().getId(),
                o.condominium().getName(), o.queuedCalls(), o.assignedCalls(), o.openIncidents(), o.queuedPanics(),
                o.deviceHealth(), o.connectivity())).toList();
    }

    @PostMapping("/condominiums/{condominiumId}/calls")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/calls`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public CallResponse createCall(@PathVariable UUID condominiumId, @Valid @RequestBody CreateCallRequest r) {
        return view(service.createCall(condominiumId, r.unitId(), r.callerName()));
    }

    @PostMapping("/condominiums/{condominiumId}/me/calls")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/me/calls`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public CallResponse createResidentCall(@PathVariable UUID condominiumId,
                                           @Valid @RequestBody ResidentCallRequest r) {
        return view(service.createCallAsResident(condominiumId, r.callerName()));
    }

    @PostMapping("/condominiums/{condominiumId}/me/panic")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/me/panic`.
     *
     * @param condominiumId condomínio
     */
    public MyCallResponse panic(@PathVariable UUID condominiumId) {
        return MyCallResponse.from(service.raisePanic(condominiumId));
    }

    @GetMapping("/condominiums/{condominiumId}/me/calls")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/calls`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<MyCallResponse> myCalls(@PathVariable UUID condominiumId,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(service.myCalls(condominiumId, pageable), MyCallResponse::from);
    }

    @GetMapping("/condominiums/{condominiumId}/calls")
    /**
     * Atende `GET /condominiums/{condominiumId}/calls`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<CallResponse> listCalls(@PathVariable UUID condominiumId,
                                                @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(service.listCalls(condominiumId, pageable), this::view);
    }

    @PostMapping("/condominiums/{condominiumId}/calls/{callId}/transfer")
    /**
     * Atende `POST /condominiums/{condominiumId}/calls/{callId}/transfer`.
     *
     * @param condominiumId condomínio
     * @param callId atendimento
     * @param r corpo da requisição (validado)
     */
    public CallResponse transfer(@PathVariable UUID condominiumId, @PathVariable UUID callId,
                                 @Valid @RequestBody TransferRequest r) {
        return view(service.transfer(condominiumId, callId, r.toUserId(), r.reason()));
    }

    @PostMapping("/condominiums/{condominiumId}/calls/{callId}/close")
    /**
     * Atende `POST /condominiums/{condominiumId}/calls/{callId}/close`.
     *
     * @param condominiumId condomínio
     * @param callId atendimento
     * @param r corpo da requisição (validado)
     */
    public CallResponse close(@PathVariable UUID condominiumId, @PathVariable UUID callId,
                              @Valid @RequestBody CloseRequest r) {
        return view(service.close(condominiumId, callId, r.outcome(), r.justification()));
    }

    @GetMapping("/condominiums/{condominiumId}/calls/{callId}/events")
    /**
     * Atende `GET /condominiums/{condominiumId}/calls/{callId}/events`.
     *
     * @param condominiumId condomínio
     * @param callId atendimento
     * @return lista de resultados
     */
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
    /**
     * Atende `GET /condominiums/{condominiumId}/operators/on-duty`.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    public List<OnDutyOperatorResponse> onDuty(@PathVariable UUID condominiumId) {
        return service.onDutyOperators(condominiumId).stream()
                .map(o -> new OnDutyOperatorResponse(o.getId(), o.getDisplayName())).toList();
    }

    @PostMapping("/condominiums/{condominiumId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/incidents`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public IncidentResponse openIncident(@PathVariable UUID condominiumId,
                                         @Valid @RequestBody OpenIncidentRequest r) {
        return IncidentResponse.from(service.openIncident(condominiumId, r.callId(), r.severity(), r.title(),
                r.description()));
    }

    @PostMapping("/condominiums/{condominiumId}/incidents/{incidentId}/resolve")
    /**
     * Atende `POST /condominiums/{condominiumId}/incidents/{incidentId}/resolve`.
     *
     * @param condominiumId condomínio
     * @param incidentId ocorrência
     */
    public IncidentResponse resolveIncident(@PathVariable UUID condominiumId, @PathVariable UUID incidentId) {
        return IncidentResponse.from(service.resolveIncident(condominiumId, incidentId));
    }

    @GetMapping("/condominiums/{condominiumId}/incidents")
    /**
     * Atende `GET /condominiums/{condominiumId}/incidents`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<IncidentResponse> incidents(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "openedAt") Pageable pageable) {
        return PageResponse.of(service.listIncidents(condominiumId, pageable), IncidentResponse::from);
    }
}
