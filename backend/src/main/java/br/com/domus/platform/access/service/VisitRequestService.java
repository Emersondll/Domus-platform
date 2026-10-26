package br.com.domus.platform.access.service;

import br.com.domus.platform.access.entity.AccessAttempt;
import br.com.domus.platform.access.entity.VisitRequest;
import br.com.domus.platform.access.repository.AccessAttemptRepository;
import br.com.domus.platform.access.repository.VisitRequestRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.config.CorrelationIdFilter;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Autorização de visitante pelo celular: a portaria pede, o morador da unidade aprova ou nega. A resposta
 * <b>não abre nada</b>: quem abre a porta continua sendo o operador, com justificativa e comando confirmado por evento.
 */
@Service
public class VisitRequestService {

    /** Prazo para o morador responder; depois disso o pedido vale como expirado. */
    static final Duration RESPONSE_WINDOW = Duration.ofMinutes(5);

    private final VisitRequestRepository requests;
    private final CommercialService commercial;
    private final ResidentRepository residents;
    private final NotificationService notifications;
    private final AccessService access;
    private final AuditService audit;
    private final AccessAttemptRepository attempts;
    private final VisitSnapshotService snapshots;
    private final Clock clock;

    public VisitRequestService(VisitRequestRepository requests, AccessAttemptRepository attempts,
                               VisitSnapshotService snapshots,
                               CommercialService commercial,
                               ResidentRepository residents, NotificationService notifications,
                               AccessService access, AuditService audit, Clock clock) {
        this.requests = requests;
        this.attempts = attempts;
        this.snapshots = snapshots;
        this.commercial = commercial;
        this.residents = residents;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public VisitRequest request(UUID condominiumId, UUID unitId, String visitorName, String kind, UUID callId,
                                UUID snapshotId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "visit.request");
        commercial.requireUnit(condominiumId, unitId);
        Instant now = Instant.now(clock);
        VisitRequest request = requests.save(new VisitRequest(condominiumId, unitId, callId, visitorName, kind,
                actor.userId(), now, now.plus(RESPONSE_WINDOW)));
        audit.record(actor.userId(), null, condominiumId, "visit.request", "visit_request",
                request.getId().toString(), AuditOutcome.SUCCESS);
        if (snapshotId != null) {
            snapshots.attach(condominiumId, snapshotId, request.getId(), actor.userId());
        }
        boolean provider = "SERVICE_PROVIDER".equals(kind);
        notifications.notifyUnit(condominiumId, unitId, NotificationService.VISIT_REQUEST,
                provider ? "Prestador de serviço na portaria" : "Visita na portaria",
                visitorName + " aguarda a sua autorização" + (snapshotId != null ? " (com imagem da entrada)" : "")
                        + ". Responda pelo app em até 5 minutos.");
        return request;
    }

    @Transactional(readOnly = true)
    public Page<VisitRequest> list(UUID condominiumId, UUID callId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "visit.list");
        return callId == null ? requests.findByCondominiumId(condominiumId, pageable)
                : requests.findByCondominiumIdAndCallId(condominiumId, callId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<VisitRequest> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "visit.list.self");
        return requests.findByCondominiumIdAndUnitId(condominiumId, myUnit(me, condominiumId), pageable);
    }

    @Transactional
    public VisitRequest decide(UUID condominiumId, UUID requestId, boolean approve) {
        String action = approve ? "visit.approve" : "visit.deny";
        ResidentContext me = access.requireResident(condominiumId, action);
        VisitRequest request = requests.findByIdAndCondominiumId(requestId, condominiumId)
                .filter(r -> r.getUnitId().equals(myUnit(me, condominiumId)))
                .orElseThrow(() -> new NotFoundException("Visit request not found"));
        Instant now = Instant.now(clock);
        if (!VisitRequest.PENDING.equals(request.effectiveStatus(now))) {
            throw new ConflictException("This request was already answered or has expired");
        }
        request.decide(approve, me.actor().userId(), now);
        audit.record(me.actor().userId(), null, condominiumId, action, "visit_request", requestId.toString(),
                AuditOutcome.SUCCESS);
        return request;
    }

    /**
     * Registra a entrada de quem o morador autorizou na portaria (sem convite). Passa a contar como "no local" e pode ter a
     * saída registrada. Não abre porta: a liberação física segue sendo da portaria. Uma entrada por pedido aprovado.
     */
    @Transactional
    public AccessAttempt registerEntry(UUID condominiumId, UUID requestId, String entrance) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "visit.entry");
        VisitRequest request = requests.findByIdAndCondominiumId(requestId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Visit request not found"));
        if (!VisitRequest.APPROVED.equals(request.effectiveStatus(Instant.now(clock)))) {
            throw new ConflictException("Only an approved request can have an entry");
        }
        if (attempts.existsByVisitRequestId(requestId)) {
            throw new ConflictException("Entry already registered for this request");
        }
        AccessAttempt attempt = attempts.save(AccessAttempt.walkIn(condominiumId, requestId, entrance, actor.userId(),
                MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now(clock)));
        audit.record(actor.userId(), null, condominiumId, "visit.entry", "access_attempt", attempt.getId().toString(),
                AuditOutcome.SUCCESS);
        notifications.notifyUnit(condominiumId, request.getUnitId(), NotificationService.VISITOR_ARRIVED,
                request.getVisitorName() + " entrou", "A portaria registrou a entrada de " + request.getVisitorName() + ".");
        return attempt;
    }

    /** Dos pedidos informados, os que já tiveram a entrada registrada. */
    @Transactional(readOnly = true)
    public Set<UUID> enteredIds(UUID condominiumId, Collection<UUID> requestIds) {
        return requestIds.isEmpty() ? Set.of() : Set.copyOf(attempts.findEnteredRequestIds(condominiumId, requestIds));
    }

    /** Imagem da entrada de um pedido da própria unidade do morador. */
    @Transactional
    public byte[] snapshotForResident(UUID condominiumId, UUID requestId) {
        ResidentContext me = access.requireResident(condominiumId, "snapshot.view.self");
        return snapshots.imageForResident(condominiumId, requestId, myUnit(me, condominiumId));
    }

    /** Dos pedidos informados, os que têm imagem da entrada. */
    @Transactional(readOnly = true)
    public Set<UUID> withSnapshot(UUID condominiumId, Collection<UUID> requestIds) {
        return snapshots.requestsWithSnapshot(condominiumId, requestIds);
    }

    private UUID myUnit(ResidentContext me, UUID condominiumId) {
        return residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
    }
}
