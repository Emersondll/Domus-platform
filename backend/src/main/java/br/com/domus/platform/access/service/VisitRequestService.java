package br.com.domus.platform.access.service;

import br.com.domus.platform.access.entity.VisitRequest;
import br.com.domus.platform.access.repository.VisitRequestRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
    private final Clock clock;

    public VisitRequestService(VisitRequestRepository requests, CommercialService commercial,
                               ResidentRepository residents, NotificationService notifications,
                               AccessService access, AuditService audit, Clock clock) {
        this.requests = requests;
        this.commercial = commercial;
        this.residents = residents;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public VisitRequest request(UUID condominiumId, UUID unitId, String visitorName, String kind, UUID callId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "visit.request");
        commercial.requireUnit(condominiumId, unitId);
        Instant now = Instant.now(clock);
        VisitRequest request = requests.save(new VisitRequest(condominiumId, unitId, callId, visitorName, kind,
                actor.userId(), now, now.plus(RESPONSE_WINDOW)));
        audit.record(actor.userId(), null, condominiumId, "visit.request", "visit_request",
                request.getId().toString(), AuditOutcome.SUCCESS);
        notifications.notifyUnit(condominiumId, unitId, NotificationService.VISIT_REQUEST,
                "Visitante na portaria", visitorName + " aguarda a sua autorização. Responda pelo app em até 5 minutos.");
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

    private UUID myUnit(ResidentContext me, UUID condominiumId) {
        return residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow().getUnitId();
    }
}
