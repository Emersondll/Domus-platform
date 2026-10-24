package br.com.domus.platform.operations.service;

import br.com.domus.platform.operations.entity.CallEvent;
import br.com.domus.platform.operations.entity.Incident;
import br.com.domus.platform.operations.entity.ServiceCall;
import br.com.domus.platform.operations.entity.Shift;
import br.com.domus.platform.operations.repository.CallEventRepository;
import br.com.domus.platform.operations.repository.IncidentRepository;
import br.com.domus.platform.operations.repository.ServiceCallRepository;
import br.com.domus.platform.operations.repository.ShiftRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.ConnectivityLookup;
import br.com.domus.platform.shared.DeviceHealthLookup;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.shared.crypto.FieldCipher;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.ForbiddenException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso da central: turnos, fila de atendimentos, decisões, incidentes e visão geral. */
@Service
public class OperationsService {

    /** Desfechos que liberam ou negam a entrada de alguém exigem justificativa registrada. */
    static final Set<String> SENSITIVE_OUTCOMES = Set.of("ENTRY_GRANTED", "ENTRY_DENIED");
    static final Set<String> OUTCOMES = Set.of("RESOLVED", "ENTRY_GRANTED", "ENTRY_DENIED", "NO_ANSWER", "FALSE_ALARM");

    /** Texto mostrado ao morador por desfecho; não inclui a justificativa interna do operador. */
    static final java.util.Map<String, String> OUTCOME_MESSAGES = java.util.Map.of(
            "RESOLVED", "Atendimento concluído.",
            "ENTRY_GRANTED", "Entrada liberada pela portaria.",
            "ENTRY_DENIED", "Entrada não autorizada pela portaria.",
            "NO_ANSWER", "A portaria não conseguiu contato.",
            "FALSE_ALARM", "Chamado encerrado sem ocorrência.");

    /** Resumo operacional de um condomínio. */
    public record Overview(Condominium condominium, long queuedCalls, long assignedCalls, long openIncidents,
                           String deviceHealth, String connectivity) {
    }

    private final ShiftRepository shifts;
    private final ServiceCallRepository calls;
    private final CallEventRepository events;
    private final IncidentRepository incidents;
    private final MembershipRepository memberships;
    private final ResidentRepository residents;
    private final CommercialService commercial;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final DeviceHealthLookup deviceHealth;
    private final ConnectivityLookup connectivity;
    private final FieldCipher cipher;
    private final NotificationService notifications;

    public OperationsService(ShiftRepository shifts, ServiceCallRepository calls, CallEventRepository events,
                             IncidentRepository incidents, MembershipRepository memberships,
                             ResidentRepository residents, CommercialService commercial, AccessService access,
                             AuditService audit, Clock clock, DeviceHealthLookup deviceHealth,
                             ConnectivityLookup connectivity, FieldCipher cipher,
                             NotificationService notifications) {
        this.shifts = shifts;
        this.calls = calls;
        this.events = events;
        this.incidents = incidents;
        this.memberships = memberships;
        this.residents = residents;
        this.commercial = commercial;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.deviceHealth = deviceHealth;
        this.connectivity = connectivity;
        this.cipher = cipher;
        this.notifications = notifications;
    }

    // ---- turnos ----

    @Transactional
    public Shift startShift() {
        Actor actor = requireOperator();
        if (shifts.findByUserIdAndEndedAtIsNull(actor.userId()).isPresent()) {
            throw new ConflictException("Shift already open");
        }
        Shift shift = shifts.save(new Shift(actor.userId(), now()));
        audit.record(actor.userId(), null, null, "shift.start", "shift", shift.getId().toString(),
                AuditOutcome.SUCCESS);
        return shift;
    }

    @Transactional
    public Shift endShift() {
        Actor actor = requireOperator();
        Shift shift = shifts.findByUserIdAndEndedAtIsNull(actor.userId())
                .orElseThrow(() -> new NotFoundException("No open shift"));
        if (!calls.findByAssignedUserIdAndStatus(actor.userId(), ServiceCall.ASSIGNED, Pageable.ofSize(1))
                .isEmpty()) {
            throw new ConflictException("Close or transfer active calls before ending the shift");
        }
        shift.end(now());
        audit.record(actor.userId(), null, null, "shift.end", "shift", shift.getId().toString(),
                AuditOutcome.SUCCESS);
        return shift;
    }

    @Transactional(readOnly = true)
    public Optional<Shift> currentShift() {
        return shifts.findByUserIdAndEndedAtIsNull(requireOperator().userId());
    }

    // ---- atendimentos ----

    @Transactional
    public ServiceCall createCall(UUID condominiumId, UUID unitId, String callerName) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "call.create");
        if (unitId != null) {
            commercial.requireUnit(condominiumId, unitId);
        }
        return enqueue(condominiumId, unitId, "MANUAL", callerName, actor.userId());
    }

    @Transactional
    public ServiceCall createCallAsResident(UUID condominiumId, String callerName) {
        ResidentContext me = access.requireResident(condominiumId, "call.create.self");
        UUID unitId = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
        return enqueue(condominiumId, unitId, "RESIDENT_APP", callerName, me.actor().userId());
    }

    @Transactional
    public Optional<ServiceCall> claimNext() {
        Actor actor = requireOperator();
        if (shifts.findByUserIdAndEndedAtIsNull(actor.userId()).isEmpty()) {
            throw new ConflictException("Start a shift before taking calls");
        }
        Set<UUID> condominiums = access.operatorCondominiumIds(actor);
        if (condominiums.isEmpty()) {
            return Optional.empty();
        }
        Optional<UUID> id = calls.claimNext(actor.userId(), condominiums, now());
        id.ifPresent(callId -> {
            ServiceCall call = calls.findById(callId).orElseThrow();
            addEvent(call, actor.userId(), "CLAIMED", null);
            notifyResident(call, NotificationService.CALL_ASSIGNED, "Sua chamada está sendo atendida",
                    "Um operador da central assumiu o seu pedido.");
        });
        return id.flatMap(calls::findById);
    }

    @Transactional(readOnly = true)
    public Page<ServiceCall> myActiveCalls(Pageable pageable) {
        return calls.findByAssignedUserIdAndStatus(requireOperator().userId(), ServiceCall.ASSIGNED, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ServiceCall> listCalls(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "call.list");
        return calls.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional
    public ServiceCall transfer(UUID condominiumId, UUID callId, UUID toUserId, String reason) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "call.transfer");
        ServiceCall call = assignedCall(condominiumId, callId, actor);
        boolean eligible = memberships.existsByUserIdAndRoleAndCondominiumIdAndActiveTrue(toUserId, Role.OPERATOR,
                condominiumId) && shifts.findByUserIdAndEndedAtIsNull(toUserId).isPresent();
        if (!eligible || toUserId.equals(actor.userId())) {
            throw new ConflictException("Target must be another operator of this condominium with an open shift");
        }
        call.transferTo(toUserId, now());
        addEvent(call, actor.userId(), "TRANSFERRED", "to " + toUserId + ": " + reason);
        audit.record(actor.userId(), null, condominiumId, "call.transfer", "call", callId.toString(),
                AuditOutcome.SUCCESS);
        return call;
    }

    @Transactional
    public ServiceCall close(UUID condominiumId, UUID callId, String outcome, String justification) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "call.close");
        if (!OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("Unknown outcome");
        }
        if (SENSITIVE_OUTCOMES.contains(outcome) && (justification == null || justification.trim().length() < 5)) {
            throw new IllegalArgumentException("Justification required for this outcome");
        }
        ServiceCall call = assignedCall(condominiumId, callId, actor);
        call.close(outcome, now());
        addEvent(call, actor.userId(), "CLOSED", outcome + (justification == null ? "" : ": " + justification));
        audit.record(actor.userId(), null, condominiumId, "call.close." + outcome, "call", callId.toString(),
                AuditOutcome.SUCCESS);
        notifyResident(call, NotificationService.CALL_CLOSED, "Sua chamada foi encerrada", OUTCOME_MESSAGES.get(outcome));
        return call;
    }

    /** Chamadas da unidade do morador (as mais recentes primeiro), sem expor o operador nem a justificativa. */
    @Transactional(readOnly = true)
    public Page<ServiceCall> myCalls(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "call.list.self");
        UUID unitId = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
        return calls.findByCondominiumIdAndUnitId(condominiumId, unitId, pageable);
    }

    @Transactional(readOnly = true)
    public List<CallEvent> callEvents(UUID condominiumId, UUID callId) {
        access.require(condominiumId, AccessLevel.READ, "call.events");
        calls.findByIdAndCondominiumId(callId, condominiumId).orElseThrow(() -> new NotFoundException("Call not found"));
        return events.findByCallIdAndCondominiumIdOrderByOccurredAtAsc(callId, condominiumId);
    }

    @Transactional(readOnly = true)
    public List<MembershipRepository.OnDutyOperator> onDutyOperators(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.OPERATE, "operator.list");
        // a consulta nativa devolve o nome cifrado: decifra aqui
        return memberships.findOnDutyOperators(condominiumId).stream()
                .<MembershipRepository.OnDutyOperator>map(o -> new MembershipRepository.OnDutyOperator() {
                    @Override
                    public UUID getId() {
                        return o.getId();
                    }

                    @Override
                    public String getDisplayName() {
                        return cipher.decrypt(o.getDisplayName());
                    }
                })
                .sorted(java.util.Comparator.comparing(MembershipRepository.OnDutyOperator::getDisplayName))
                .toList();
    }

    // ---- incidentes ----

    @Transactional
    public Incident openIncident(UUID condominiumId, UUID callId, String severity, String title, String description) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "incident.open");
        if (callId != null) {
            calls.findByIdAndCondominiumId(callId, condominiumId)
                    .orElseThrow(() -> new NotFoundException("Call not found"));
        }
        Incident incident = incidents.save(new Incident(condominiumId, callId, severity, title, description,
                actor.userId(), now()));
        audit.record(actor.userId(), null, condominiumId, "incident.open", "incident",
                incident.getId().toString(), AuditOutcome.SUCCESS);
        return incident;
    }

    @Transactional
    public Incident resolveIncident(UUID condominiumId, UUID incidentId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "incident.resolve");
        Incident incident = incidents.findByIdAndCondominiumId(incidentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Incident not found"));
        if (Incident.RESOLVED.equals(incident.getStatus())) {
            throw new ConflictException("Incident already resolved");
        }
        incident.resolve(now());
        audit.record(actor.userId(), null, condominiumId, "incident.resolve", "incident",
                incidentId.toString(), AuditOutcome.SUCCESS);
        return incident;
    }

    @Transactional(readOnly = true)
    public Page<Incident> listIncidents(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "incident.list");
        return incidents.findByCondominiumId(condominiumId, pageable);
    }

    // ---- visão geral ----

    @Transactional(readOnly = true)
    public List<Overview> overview() {
        return commercial.listCondominiums(PageRequest.of(0, 200)).getContent().stream()
                .map(c -> new Overview(c, calls.countByStatus(c.getId(), ServiceCall.QUEUED),
                        calls.countByStatus(c.getId(), ServiceCall.ASSIGNED),
                        incidents.countByCondominiumIdAndStatus(c.getId(), Incident.OPEN),
                        deviceHealth.healthSummary(c.getId()), connectivity.connectivitySummary(c.getId())))
                .toList();
    }

    /** Verdadeiro se o atendimento está em andamento e atribuído ao usuário, naquele condomínio. */
    @Transactional(readOnly = true)
    public boolean isCallAssignedTo(UUID condominiumId, UUID callId, UUID userId) {
        return calls.findByIdAndCondominiumId(callId, condominiumId)
                .filter(c -> ServiceCall.ASSIGNED.equals(c.getStatus()) && userId.equals(c.getAssignedUserId()))
                .isPresent();
    }

    // ---- internos ----

    /** Só pedidos feitos pelo app geram retorno: quem pediu precisa saber que foi atendido e como terminou. */
    private void notifyResident(ServiceCall call, String type, String title, String body) {
        if ("RESIDENT_APP".equals(call.getSource()) && call.getUnitId() != null) {
            notifications.notifyUnit(call.getCondominiumId(), call.getUnitId(), type, title, body);
        }
    }

    private ServiceCall enqueue(UUID condominiumId, UUID unitId, String source, String callerName, UUID actorId) {
        ServiceCall call = calls.save(new ServiceCall(condominiumId, unitId, source, callerName, now()));
        addEvent(call, actorId, "CREATED", source);
        return call;
    }

    private ServiceCall assignedCall(UUID condominiumId, UUID callId, Actor actor) {
        ServiceCall call = calls.findByIdAndCondominiumId(callId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Call not found"));
        if (!ServiceCall.ASSIGNED.equals(call.getStatus())) {
            throw new ConflictException("Call is not in progress");
        }
        if (!actor.userId().equals(call.getAssignedUserId())) {
            audit.record(actor.userId(), null, condominiumId, "call.act.not-assignee", "call",
                    callId.toString(), AuditOutcome.DENIED);
            throw new ForbiddenException("Call is assigned to another operator");
        }
        return call;
    }

    private Actor requireOperator() {
        Actor actor = access.currentActor();
        if (access.operatorCondominiumIds(actor).isEmpty()) {
            throw new ForbiddenException("Operator role required");
        }
        return actor;
    }

    private void addEvent(ServiceCall call, UUID actorId, String type, String detail) {
        events.save(new CallEvent(call.getId(), call.getCondominiumId(), actorId, type, detail, now()));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
