package br.com.domus.platform.identity.service;

import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.AssociationRequest;
import br.com.domus.platform.identity.entity.AssociationStatus;
import br.com.domus.platform.identity.entity.JoinCode;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.AssociationRequestRepository;
import br.com.domus.platform.identity.repository.JoinCodeRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.ForbiddenException;
import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.error.TooManyRequestsException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.CondominiumLookup;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Associação do morador ao condomínio: o síndico gera um código de adesão; quem tem o código (e já se autenticou no
 * provedor de identidade) pede para entrar; o síndico aprova, escolhendo a unidade, ou recusa. Enquanto não houver
 * aprovação o pedido não dá nenhum acesso. O código só abre um pedido, nunca um acesso.
 */
@Service
public class AssociationService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // sem 0/O/1/I, para ditar por telefone
    private static final int CODE_LENGTH = 10;
    private static final int MAX_ATTEMPTS = 8;
    private static final long WINDOW_MS = 15 * 60_000L;

    private record Window(long start, int count) {
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private final JoinCodeRepository codes;
    private final AssociationRequestRepository requests;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final ResidentRepository residents;
    private final CondominiumRepository condominiums;
    private final CondominiumLookup lookup;
    private final CommercialService commercial;
    private final MemberService members;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    /** Construtor de AssociationService; recebe as dependências por injeção. */
    public AssociationService(JoinCodeRepository codes, AssociationRequestRepository requests, AppUserRepository users,
                              MembershipRepository memberships, ResidentRepository residents,
                              CondominiumRepository condominiums, CondominiumLookup lookup,
                              CommercialService commercial, MemberService members, AccessService access,
                              AuditService audit, Clock clock) {
        this.codes = codes;
        this.requests = requests;
        this.users = users;
        this.memberships = memberships;
        this.residents = residents;
        this.condominiums = condominiums;
        this.lookup = lookup;
        this.commercial = commercial;
        this.members = members;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Pedido do próprio solicitante, com o nome do condomínio para ele conferir.
     *
     * @param request pedido
     * @param condominiumName nome do condomínio
     */
    public record Mine(AssociationRequest request, String condominiumName) {
    }

    /**
     * Código de adesão atual do condomínio, se houver.
     *
     * @param condominiumId condomínio
     */
    @Transactional(readOnly = true)
    public Optional<String> joinCode(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.MANAGE, "join-code.read");
        return codes.findById(condominiumId).map(JoinCode::getCode);
    }

    /**
     * Gera (ou troca) o código de adesão; o anterior deixa de valer.
     *
     * @param condominiumId condomínio
     */
    @Transactional
    public String rotateJoinCode(UUID condominiumId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "join-code.rotate");
        String code = newCode();
        JoinCode entry = codes.findById(condominiumId).orElse(null);
        if (entry == null) {
            codes.save(new JoinCode(condominiumId, code));
        } else {
            entry.rotate(code);
        }
        audit.record(actor.userId(), null, condominiumId, "join-code.rotate", "condominium",
                condominiumId.toString(), AuditOutcome.SUCCESS);
        return code;
    }

    /**
     * Desliga o código de adesão: ninguém mais consegue pedir associação até gerar outro.
     *
     * @param condominiumId condomínio
     */
    @Transactional
    public void disableJoinCode(UUID condominiumId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "join-code.disable");
        codes.findById(condominiumId).ifPresent(codes::delete);
        audit.record(actor.userId(), null, condominiumId, "join-code.disable", "condominium",
                condominiumId.toString(), AuditOutcome.SUCCESS);
    }

    /**
     * A pessoa autenticada pede para se associar ao condomínio do código.
     *
     * @param code código de adesão
     * @param fullName nome completo
     * @param email e-mail
     * @param phone telefone, opcional
     * @param type vínculo pedido (proprietário ou locatário)
     * @param unitText unidade como a pessoa a descreve (o síndico escolhe a unidade real ao aprovar)
     */
    @Transactional
    public Mine request(String code, String fullName, String email, String phone, ResidentType type, String unitText) {
        String subject = access.currentSubject();
        if (type != ResidentType.OWNER && type != ResidentType.TENANT) {
            throw new InvalidRequestException("Only owners and tenants can request access; dependents have no account");
        }
        throttle(subject);
        AppUser existing = users.findByExternalSubject(subject).orElse(null);
        if (existing != null && !existing.isActive()) {
            throw new ForbiddenException("User is not registered or is inactive");
        }
        UUID condominiumId = codes.findByCode(code == null ? "" : code.trim().toUpperCase(Locale.ROOT))
                .map(JoinCode::getCondominiumId).filter(id -> !lookup.isSuspended(id)).orElse(null);
        if (condominiumId == null) {
            audit.record(existing == null ? null : existing.getId(), null, null, "association.request", "join-code",
                    null, AuditOutcome.DENIED);
            throw new NotFoundException("Join code not found");
        }
        if (existing != null && memberships.findByUserIdAndActiveTrue(existing.getId()).stream()
                .anyMatch(m -> m.getRole() == Role.RESIDENT && condominiumId.equals(m.getCondominiumId()))) {
            throw new ConflictException("You already have access to this condominium");
        }
        if (requests.existsByCondominiumIdAndExternalSubjectAndStatus(condominiumId, subject, AssociationStatus.PENDING)) {
            throw new ConflictException("You already have a pending request for this condominium");
        }
        AssociationRequest saved = requests.save(new AssociationRequest(condominiumId, subject, fullName.trim(),
                email.trim(), blankToNull(phone), type, unitText.trim()));
        audit.record(existing == null ? null : existing.getId(), null, condominiumId, "association.request",
                "association_request", saved.getId().toString(), AuditOutcome.SUCCESS);
        return new Mine(saved, condominiums.findById(condominiumId).map(c -> c.getName()).orElse(""));
    }

    /** Pedidos do próprio solicitante (funciona antes de ter qualquer acesso). */
    @Transactional(readOnly = true)
    public List<Mine> mine() {
        String subject = access.currentSubject();
        return requests.findByExternalSubjectOrderByCreatedAtDesc(subject).stream()
                .map(r -> new Mine(r, condominiums.findById(r.getCondominiumId()).map(c -> c.getName()).orElse("")))
                .toList();
    }

    /**
     * Lista os pedidos do condomínio (dados pessoais: a leitura é auditada).
     *
     * @param condominiumId condomínio
     * @param status situação; pendentes quando omitida
     */
    @Transactional
    public List<AssociationRequest> list(UUID condominiumId, AssociationStatus status) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "association.list");
        List<AssociationRequest> found = requests.findByCondominiumIdAndStatusOrderByCreatedAtDesc(condominiumId,
                status == null ? AssociationStatus.PENDING : status);
        audit.record(actor.userId(), null, condominiumId, "association.list", "association_request", null,
                AuditOutcome.SUCCESS);
        return found;
    }

    /**
     * Aprova o pedido: cria o cadastro do morador na unidade escolhida e concede o acesso ao app.
     *
     * @param condominiumId condomínio
     * @param requestId pedido
     * @param unitId unidade real escolhida pelo síndico
     */
    @Transactional
    public AssociationRequest approve(UUID condominiumId, UUID requestId, UUID unitId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "association.approve");
        AssociationRequest r = pending(condominiumId, requestId);
        commercial.requireUnit(condominiumId, unitId);
        Resident resident = residents.save(new Resident(condominiumId, unitId, r.getFullName(), r.getEmail(),
                r.getPhone(), r.getRequestedType()));
        AppUser user = members.upsertUser(r.getExternalSubject(), r.getEmail(), r.getFullName());
        Membership m = memberships.save(Membership.forResident(user.getId(), condominiumId, resident.getId()));
        r.approve(actor.userId(), resident.getId(), Instant.now(clock));
        audit.record(actor.userId(), null, condominiumId, "association.approve", "association_request",
                requestId.toString(), AuditOutcome.SUCCESS);
        audit.record(actor.userId(), null, condominiumId, "member.resident.link", "membership", m.getId().toString(),
                AuditOutcome.SUCCESS);
        return r;
    }

    /**
     * Recusa o pedido; nada é criado.
     *
     * @param condominiumId condomínio
     * @param requestId pedido
     */
    @Transactional
    public AssociationRequest reject(UUID condominiumId, UUID requestId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "association.reject");
        AssociationRequest r = pending(condominiumId, requestId);
        r.reject(actor.userId(), Instant.now(clock));
        audit.record(actor.userId(), null, condominiumId, "association.reject", "association_request",
                requestId.toString(), AuditOutcome.SUCCESS);
        return r;
    }

    private AssociationRequest pending(UUID condominiumId, UUID requestId) {
        AssociationRequest r = requests.lockByIdAndCondominiumId(requestId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Request not found"));
        if (r.getStatus() != AssociationStatus.PENDING) {
            throw new ConflictException("Request was already decided");
        }
        return r;
    }

    /** Limita as tentativas de código por pessoa (o código tem entropia alta, mas não deve ser adivinhável por força bruta). */
    private void throttle(String subject) {
        long now = clock.millis();
        Window w = attempts.merge(subject, new Window(now, 1),
                (old, fresh) -> now - old.start() > WINDOW_MS ? fresh : new Window(old.start(), old.count() + 1));
        if (w.count() > MAX_ATTEMPTS) {
            throw new TooManyRequestsException("Too many attempts; try again later");
        }
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(e -> now - e.getValue().start() > WINDOW_MS);
        }
    }

    private String newCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (codes.findByCode(sb.toString()).isEmpty()) {
                return sb.toString();
            }
        }
        throw new ConflictException("Could not generate a unique code; try again");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
