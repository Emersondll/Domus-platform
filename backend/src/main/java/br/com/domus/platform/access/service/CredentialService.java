package br.com.domus.platform.access.service;

import br.com.domus.platform.access.entity.AccessAttempt;
import br.com.domus.platform.access.entity.AccessCredential;
import br.com.domus.platform.access.entity.Visitor;
import br.com.domus.platform.access.repository.AccessAttemptRepository;
import br.com.domus.platform.access.repository.AccessCredentialRepository;
import br.com.domus.platform.access.repository.VisitorRepository;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.shared.PolicyVersion;
import io.micrometer.core.instrument.MeterRegistry;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.config.CorrelationIdFilter;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Convites (credenciais temporárias) do morador e validação na portaria. Um resultado GRANTED significa
 * "autorização válida e uso consumido"; a abertura física é um passo separado, que só é considerado
 * concluído com confirmação do dispositivo (Fase 6).
 */
@Service
public class CredentialService {

    static final Duration MAX_VALIDITY = Duration.ofDays(7);
    static final Duration START_TOLERANCE = Duration.ofMinutes(5);
    static final int MAX_USES = 20;

    private final AccessCredentialRepository credentials;
    private final VisitorRepository visitors;
    private final AccessAttemptRepository attempts;
    private final ResidentRepository residents;
    private final CredentialTokenCodec codec;
    private final br.com.domus.platform.shared.tenant.AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final NotificationService notifications;
    private final PolicyVersion policyVersion;
    private final MeterRegistry meters;

    /** Construtor de CredentialService; recebe as dependências por injeção. */
    public CredentialService(AccessCredentialRepository credentials, VisitorRepository visitors,
                             AccessAttemptRepository attempts, ResidentRepository residents,
                             CredentialTokenCodec codec,
                             br.com.domus.platform.shared.tenant.AccessService access, AuditService audit,
                             Clock clock, NotificationService notifications, PolicyVersion policyVersion,
                             MeterRegistry meters) {
        this.credentials = credentials;
        this.visitors = visitors;
        this.attempts = attempts;
        this.residents = residents;
        this.codec = codec;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
        this.policyVersion = policyVersion;
        this.meters = meters;
    }

    /** Convite criado, com o token em claro (devolvido uma única vez, nunca armazenado). */
    public record Issued(AccessCredential credential, Visitor visitor, String token) {
    }

    /** Credencial elegível para validação offline pelo agente (assinatura já calculada na central). */
    public record OfflineCredential(UUID id, String sig, Instant validFrom, Instant validUntil, int maxUses,
                                    int usesSoFar) {
    }

    /** Resultado da aplicação de um uso feito offline; {@code conflictKind} nulo = sem divergência. */
    public record OfflineOutcome(String conflictKind, String detail) {
    }

    /** Resultado da validação de um token na portaria. */
    public record Validation(boolean granted, String reason, UUID unitId, String visitorName, UUID attemptId) {
    }

    @Transactional
    /**
     * Emite o convite de acesso de um visitante ou prestador para a unidade do morador.
     *
     * @param condominiumId condomínio
     * @param visitorName nome do visitante
     * @param kind tipo
     * @param validFrom início da validade
     * @param validUntil fim da validade
     * @param maxUses max uses
     */
    public Issued issueInvitation(UUID condominiumId, String visitorName, String kind, Instant validFrom,
                                  Instant validUntil, int maxUses) {
        ResidentContext me = access.requireResident(condominiumId, "invitation.create");
        Instant now = Instant.now(clock);
        if (!validUntil.isAfter(validFrom) || validFrom.isBefore(now.minus(START_TOLERANCE))
                || Duration.between(validFrom, validUntil).compareTo(MAX_VALIDITY) > 0
                || maxUses < 1 || maxUses > MAX_USES) {
            throw new IllegalArgumentException("Invalid invitation window or use limit");
        }
        UUID unitId = unitOf(me);
        Visitor visitor = visitors.save(new Visitor(condominiumId, unitId, visitorName, kind));
        AccessCredential credential = credentials.save(new AccessCredential(condominiumId, unitId,
                visitor.getId(), validFrom, validUntil, maxUses, me.actor().userId()));
        audit.record(me.actor().userId(), null, condominiumId, "invitation.create", "credential",
                credential.getId().toString(), AuditOutcome.SUCCESS);
        policyVersion.bump(condominiumId);
        return new Issued(credential, visitor, codec.encode(credential.getId(), condominiumId));
    }

    @Transactional(readOnly = true)
    /**
     * Lista os convites emitidos pelo morador autenticado.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<AccessCredential> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "invitation.list");
        return credentials.findByCondominiumIdAndUnitId(condominiumId, unitOf(me), pageable);
    }

    @Transactional
    /**
     * Revoga um convite do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param credentialId credencial
     */
    public AccessCredential revokeMine(UUID condominiumId, UUID credentialId) {
        ResidentContext me = access.requireResident(condominiumId, "invitation.revoke");
        UUID unitId = unitOf(me);
        AccessCredential credential = credentials.findByIdAndCondominiumId(credentialId, condominiumId)
                .filter(c -> c.getUnitId().equals(unitId))
                .orElseThrow(() -> new NotFoundException("Invitation not found"));
        credential.revoke(Instant.now(clock));
        audit.record(me.actor().userId(), null, condominiumId, "invitation.revoke", "credential",
                credentialId.toString(), AuditOutcome.SUCCESS);
        policyVersion.bump(condominiumId);
        return credential;
    }

    @Transactional
    /**
     * Valida o token apresentado na portaria e registra a tentativa; não aciona nenhuma porta.
     *
     * @param condominiumId condomínio
     * @param token token apresentado
     * @param entrance ponto de acesso
     */
    public Validation validate(UUID condominiumId, String token, String entrance) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "access.validate");
        Instant now = Instant.now(clock);
        Optional<UUID> id = codec.decode(token, condominiumId);
        if (id.isEmpty()) {
            UUID attemptId = record(condominiumId, null, entrance, false, "INVALID_TOKEN", actor.userId(), now);
            return new Validation(false, "INVALID_TOKEN", null, null, attemptId);
        }
        Optional<AccessCredential> found = credentials.findByIdAndCondominiumId(id.get(), condominiumId);
        if (found.isEmpty()) {
            UUID attemptId = record(condominiumId, id.get(), entrance, false, "UNKNOWN_CREDENTIAL", actor.userId(), now);
            return new Validation(false, "UNKNOWN_CREDENTIAL", null, null, attemptId);
        }
        if (credentials.consumeUse(id.get(), condominiumId, now) == 1) {
            AccessCredential c = found.get();
            String name = c.getVisitorId() == null ? null
                    : visitors.findById(c.getVisitorId()).map(Visitor::getFullName).orElse(null);
            UUID attemptId = record(condominiumId, id.get(), entrance, true, "OK", actor.userId(), now);
            notifications.notifyUnit(condominiumId, c.getUnitId(), NotificationService.VISITOR_ARRIVED,
                    "Visitante na portaria", (name == null ? "Seu convidado" : name) + " teve a entrada autorizada.");
            return new Validation(true, "OK", c.getUnitId(), name, attemptId);
        }
        String reason = denialReason(credentials.findByIdAndCondominiumId(id.get(), condominiumId)
                .orElse(found.get()), now);
        UUID attemptId = record(condominiumId, id.get(), entrance, false, reason, actor.userId(), now);
        return new Validation(false, reason, null, null, attemptId);
    }

    /** Histórico de acessos das credenciais da própria unidade do morador. */
    /** Verdadeiro se a tentativa foi concedida neste condomínio nos últimos 5 minutos. */
    @Transactional(readOnly = true)
    public boolean isFreshGrant(UUID condominiumId, UUID attemptId) {
        return attempts.findById(attemptId)
                .filter(a -> a.getCondominiumId().equals(condominiumId) && "GRANTED".equals(a.getResult())
                        && a.getOccurredAt().isAfter(Instant.now(clock).minus(Duration.ofMinutes(5))))
                .isPresent();
    }

    /** Credenciais que o agente local pode aceitar offline, com a assinatura que ele compara (sem o segredo). */
    @Transactional(readOnly = true)
    public List<OfflineCredential> offlineCredentials(UUID condominiumId, int limit) {
        Instant now = Instant.now(clock);
        return credentials.findOfflineEligible(condominiumId, now, Pageable.ofSize(limit)).stream()
                .map(c -> new OfflineCredential(c.getId(), codec.signatureOf(c.getId(), condominiumId),
                        c.getValidFrom(), c.getValidUntil(), c.getMaxUses(), c.getUseCount()))
                .toList();
    }

    /**
     * Reconcilia uma entrada concedida offline pelo agente. A entrada já ocorreu: ela é sempre registrada, e as
     * divergências (revogada antes do uso, vencida, quota estourada, desconhecida) geram um conflito para auditoria.
     */
    @Transactional
    public OfflineOutcome applyOfflineEntry(UUID condominiumId, UUID credentialId, String entrance,
                                            Instant occurredAt) {
        Optional<AccessCredential> found = credentials.findByIdAndCondominiumId(credentialId, condominiumId);
        if (found.isEmpty()) {
            attempts.save(new AccessAttempt(condominiumId, null, entrance, "GRANTED", "OFFLINE_UNKNOWN_CREDENTIAL",
                    null, MDC.get(CorrelationIdFilter.MDC_KEY), occurredAt));
            return new OfflineOutcome("UNKNOWN_CREDENTIAL", "Credential " + credentialId + " not found");
        }
        AccessCredential c = found.get();
        String conflict = null;
        String detail = null;
        if (c.getRevokedAt() != null && occurredAt.isAfter(c.getRevokedAt())) {
            conflict = "REVOKED_BEFORE_USE";
            detail = "Credential revoked at " + c.getRevokedAt() + " before the offline entry at " + occurredAt;
        } else if (!occurredAt.isBefore(c.getValidUntil()) || occurredAt.isBefore(c.getValidFrom())) {
            conflict = "EXPIRED_AT_USE";
            detail = "Entry at " + occurredAt + " outside validity window";
        }
        boolean applied = credentials.applyOfflineUse(credentialId, condominiumId) == 1;
        if (!applied && conflict == null) {
            conflict = "QUOTA_EXCEEDED";
            detail = "Credential quota already exhausted at the central";
        }
        attempts.save(new AccessAttempt(condominiumId, credentialId, entrance, "GRANTED",
                conflict == null ? "OFFLINE" : "OFFLINE_CONFLICT", null, MDC.get(CorrelationIdFilter.MDC_KEY),
                occurredAt));
        notifications.notifyUnit(condominiumId, c.getUnitId(), NotificationService.VISITOR_ARRIVED,
                "Visitante na portaria", "Entrada de seu convidado registrada pela portaria local.");
        return new OfflineOutcome(conflict, detail);
    }

    /** Registra uma negação feita offline pelo agente. */
    @Transactional
    public void recordOfflineDenial(UUID condominiumId, UUID credentialId, String entrance, String reason,
                                    Instant occurredAt) {
        attempts.save(new AccessAttempt(condominiumId, credentialId, entrance, "DENIED",
                reason == null ? "OFFLINE_DENIED" : "OFFLINE_" + reason, null,
                MDC.get(CorrelationIdFilter.MDC_KEY), occurredAt));
    }

    @Transactional(readOnly = true)
    /**
     * Lista as tentativas de acesso da unidade do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<AccessAttempt> historyOfMyUnit(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "access.history.self");
        return attempts.findByUnit(condominiumId, unitOf(me), pageable);
    }

    @Transactional(readOnly = true)
    /**
     * Lista as tentativas de acesso do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<AccessAttempt> listAttempts(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "access.attempts.list");
        return attempts.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional(readOnly = true)
    /**
     * Lista os visitantes cadastrados no condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    public Page<Visitor> listVisitors(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "visitor.list");
        return visitors.findByCondominiumId(condominiumId, pageable);
    }

    private static String denialReason(AccessCredential c, Instant now) {
        if (AccessCredential.REVOKED.equals(c.getStatus())) {
            return "REVOKED";
        }
        if (c.getUseCount() >= c.getMaxUses()) {
            return "EXHAUSTED";
        }
        if (!c.getValidUntil().isAfter(now)) {
            return "EXPIRED";
        }
        return "NOT_YET_VALID";
    }

    private UUID record(UUID condominiumId, UUID credentialId, String entrance, boolean granted, String reason,
                        UUID actorUserId, Instant now) {
        meters.counter("portaria.access.validations", "result", granted ? "granted" : "denied",
                "reason", reason.toLowerCase(java.util.Locale.ROOT)).increment();
        return attempts.save(new AccessAttempt(condominiumId, credentialId, entrance, granted ? "GRANTED" : "DENIED",
                reason, actorUserId, MDC.get(CorrelationIdFilter.MDC_KEY), now)).getId();
    }

    private UUID unitOf(ResidentContext me) {
        return residents.findByIdAndCondominiumId(me.residentId(), me.condominiumId()).orElseThrow().getUnitId();
    }
}
