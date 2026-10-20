package br.com.portaria.platform.access;

import br.com.portaria.platform.residents.ResidentRepository;
import br.com.portaria.platform.shared.audit.AuditOutcome;
import br.com.portaria.platform.shared.audit.AuditService;
import br.com.portaria.platform.shared.config.CorrelationIdFilter;
import br.com.portaria.platform.shared.error.NotFoundException;
import br.com.portaria.platform.shared.tenant.AccessLevel;
import br.com.portaria.platform.shared.tenant.Actor;
import br.com.portaria.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final br.com.portaria.platform.shared.tenant.AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public CredentialService(AccessCredentialRepository credentials, VisitorRepository visitors,
                             AccessAttemptRepository attempts, ResidentRepository residents,
                             CredentialTokenCodec codec,
                             br.com.portaria.platform.shared.tenant.AccessService access, AuditService audit,
                             Clock clock) {
        this.credentials = credentials;
        this.visitors = visitors;
        this.attempts = attempts;
        this.residents = residents;
        this.codec = codec;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /** Convite criado, com o token em claro (devolvido uma única vez, nunca armazenado). */
    public record Issued(AccessCredential credential, Visitor visitor, String token) {
    }

    /** Resultado da validação de um token na portaria. */
    public record Validation(boolean granted, String reason, UUID unitId, String visitorName) {
    }

    @Transactional
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
        return new Issued(credential, visitor, codec.encode(credential.getId(), condominiumId));
    }

    @Transactional(readOnly = true)
    public Page<AccessCredential> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "invitation.list");
        return credentials.findByCondominiumIdAndUnitId(condominiumId, unitOf(me), pageable);
    }

    @Transactional
    public AccessCredential revokeMine(UUID condominiumId, UUID credentialId) {
        ResidentContext me = access.requireResident(condominiumId, "invitation.revoke");
        UUID unitId = unitOf(me);
        AccessCredential credential = credentials.findByIdAndCondominiumId(credentialId, condominiumId)
                .filter(c -> c.getUnitId().equals(unitId))
                .orElseThrow(() -> new NotFoundException("Invitation not found"));
        credential.revoke(Instant.now(clock));
        audit.record(me.actor().userId(), null, condominiumId, "invitation.revoke", "credential",
                credentialId.toString(), AuditOutcome.SUCCESS);
        return credential;
    }

    @Transactional
    public Validation validate(UUID condominiumId, String token, String entrance) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "access.validate");
        Instant now = Instant.now(clock);
        Optional<UUID> id = codec.decode(token, condominiumId);
        if (id.isEmpty()) {
            record(condominiumId, null, entrance, false, "INVALID_TOKEN", actor.userId(), now);
            return new Validation(false, "INVALID_TOKEN", null, null);
        }
        Optional<AccessCredential> found = credentials.findByIdAndCondominiumId(id.get(), condominiumId);
        if (found.isEmpty()) {
            record(condominiumId, id.get(), entrance, false, "UNKNOWN_CREDENTIAL", actor.userId(), now);
            return new Validation(false, "UNKNOWN_CREDENTIAL", null, null);
        }
        if (credentials.consumeUse(id.get(), condominiumId, now) == 1) {
            AccessCredential c = found.get();
            String name = c.getVisitorId() == null ? null
                    : visitors.findById(c.getVisitorId()).map(Visitor::getFullName).orElse(null);
            record(condominiumId, id.get(), entrance, true, "OK", actor.userId(), now);
            return new Validation(true, "OK", c.getUnitId(), name);
        }
        String reason = denialReason(credentials.findByIdAndCondominiumId(id.get(), condominiumId)
                .orElse(found.get()), now);
        record(condominiumId, id.get(), entrance, false, reason, actor.userId(), now);
        return new Validation(false, reason, null, null);
    }

    @Transactional(readOnly = true)
    public Page<AccessAttempt> listAttempts(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "access.attempts.list");
        return attempts.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional(readOnly = true)
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

    private void record(UUID condominiumId, UUID credentialId, String entrance, boolean granted, String reason,
                        UUID actorUserId, Instant now) {
        attempts.save(new AccessAttempt(condominiumId, credentialId, entrance, granted ? "GRANTED" : "DENIED",
                reason, actorUserId, MDC.get(CorrelationIdFilter.MDC_KEY), now));
    }

    private UUID unitOf(ResidentContext me) {
        return residents.findByIdAndCondominiumId(me.residentId(), me.condominiumId()).orElseThrow().getUnitId();
    }
}
