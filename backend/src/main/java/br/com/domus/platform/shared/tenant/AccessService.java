package br.com.domus.platform.shared.tenant;

import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ForbiddenException;
import br.com.domus.platform.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Autorização de negócio no servidor. O contexto do tenant é sempre derivado das associações (membership)
 * do usuário autenticado; identificadores vindos da URL ou do corpo são apenas alvos a validar, nunca
 * fonte de autoridade.
 */
@Service
public class AccessService {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final CondominiumLookup condominiums;
    private final AuditService audit;

    /**
     * Em produção, uma associação de equipe só vale se o token trouxer o papel correspondente do Keycloak: é esse papel que
     * obriga o OTP no login. Sem isso, quem foi adicionado à equipe aqui mas esquecido lá entraria só com senha.
     */
    @Value("${portaria.security.require-staff-mfa-role:false}")
    private boolean requireStaffMfaRole;

    /** Última vez que registramos na auditoria a falta do papel, por usuário (evita uma linha por requisição do painel). */
    private final Map<UUID, Instant> mfaRoleDenialLogged = new ConcurrentHashMap<>();

    private static final Map<Role, String> KEYCLOAK_STAFF_ROLE = Map.of(Role.PLATFORM_ADMIN, "platform-admin",
            Role.COMPANY_ADMIN, "company-admin", Role.CONDO_MANAGER, "condo-manager", Role.OPERATOR, "operator");

    /** Papel de realm no Keycloak que corresponde ao papel de negócio da equipe (nulo para papéis sem MFA). */
    public static String providerRole(Role role) {
        return KEYCLOAK_STAFF_ROLE.get(role);
    }

    /** Construtor de AccessService; recebe as dependências por injeção. */
    public AccessService(AppUserRepository users, MembershipRepository memberships,
                         CondominiumLookup condominiums, AuditService audit) {
        this.users = users;
        this.memberships = memberships;
        this.condominiums = condominiums;
        this.audit = audit;
    }

    /** Identificador (sub) do usuário autenticado no provedor de identidade; vale também para quem ainda não tem cadastro. */
    public String currentSubject() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("Authentication required");
        }
        return jwt.getSubject();
    }

    /**
     * Identifica o usuário autenticado e suas associações.
     */
    @Transactional(readOnly = true)
    public Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("Authentication required");
        }
        AppUser user = users.findByExternalSubject(jwt.getSubject())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new ForbiddenException("User is not registered or is inactive"));
        List<Membership> all = memberships.findByUserIdAndActiveTrue(user.getId());
        List<Membership> notSuspended = all.stream().filter(m -> !isSuspended(m)).toList();
        if (notSuspended.isEmpty() && !all.isEmpty()) {
            // empresa ou condomínio suspenso: o acesso ao app (equipe, moradores, administradores) fica desligado
            throw new ForbiddenException("Access suspended");
        }
        List<Membership> usable = notSuspended.stream().filter(m -> hasMfaRole(m, jwt)).toList();
        if (usable.isEmpty() && !notSuspended.isEmpty()) {
            logMissingMfaRole(user.getId());
            throw new ForbiddenException("Staff role requires multi-factor authentication");
        }
        if (usable.size() < notSuspended.size()) {
            logMissingMfaRole(user.getId()); // perdeu só a parte de equipe; mantém, por exemplo, o vínculo de morador
        }
        return new Actor(user.getId(), usable);
    }

    /** Morador não precisa de papel; equipe precisa do papel do Keycloak, que é o que obriga o OTP (quando a exigência está ligada). */
    private boolean hasMfaRole(Membership m, Jwt jwt) {
        String required = KEYCLOAK_STAFF_ROLE.get(m.getRole());
        if (!requireStaffMfaRole || required == null) {
            return true;
        }
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        return realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles && roles.contains(required);
    }

    private void logMissingMfaRole(UUID userId) {
        Instant now = Instant.now();
        Instant last = mfaRoleDenialLogged.get(userId);
        if (last == null || last.isBefore(now.minus(Duration.ofMinutes(10)))) {
            mfaRoleDenialLogged.put(userId, now);
            audit.record(userId, null, null, "auth.staff-mfa-role-missing", "user", userId.toString(), AuditOutcome.DENIED);
        }
    }

    /** O vínculo pertence a uma empresa ou a um condomínio suspenso (o administrador da plataforma nunca é suspenso). */
    private boolean isSuspended(Membership m) {
        return switch (m.getRole()) {
            case PLATFORM_ADMIN -> false;
            case COMPANY_ADMIN -> m.getOperatorCompanyId() != null && condominiums.isCompanySuspended(m.getOperatorCompanyId());
            default -> m.getCondominiumId() != null && condominiums.isSuspended(m.getCondominiumId());
        };
    }

    /**
     * Calcula o nível de acesso do usuário no condomínio.
     *
     * @param actor usuário autenticado
     * @param condominiumId condomínio
     */
    public AccessLevel levelFor(Actor actor, UUID condominiumId) {
        UUID companyId = null;
        boolean companyLookedUp = false;
        AccessLevel best = AccessLevel.NONE;
        for (Membership m : actor.memberships()) {
            AccessLevel level = AccessLevel.NONE;
            switch (m.getRole()) {
                case COMPANY_ADMIN -> {
                    if (!companyLookedUp) {
                        companyId = condominiums.companyIdOf(condominiumId).orElse(null);
                        companyLookedUp = true;
                    }
                    if (companyId != null && companyId.equals(m.getOperatorCompanyId())
                            && !condominiums.isSuspended(condominiumId)) {
                        level = AccessLevel.MANAGE;
                    }
                }
                case CONDO_MANAGER -> level = condominiumId.equals(m.getCondominiumId())
                        ? AccessLevel.MANAGE : AccessLevel.NONE;
                case OPERATOR -> level = condominiumId.equals(m.getCondominiumId())
                        ? AccessLevel.OPERATE : AccessLevel.NONE;
                case PLATFORM_ADMIN, RESIDENT -> level = AccessLevel.NONE;
            }
            if (level.ordinal() > best.ordinal()) {
                best = level;
            }
        }
        return best;
    }

    /** Exige ao menos o nível informado; sem nenhum acesso responde 404, com acesso insuficiente, 403. */
    public Actor require(UUID condominiumId, AccessLevel required, String action) {
        Actor actor = currentActor();
        AccessLevel level = levelFor(actor, condominiumId);
        if (level.atLeast(required)) {
            return actor;
        }
        audit.record(actor.userId(), null, condominiumId, action, "condominium",
                condominiumId.toString(), AuditOutcome.DENIED);
        if (level == AccessLevel.NONE) {
            throw new NotFoundException("Condominium not found");
        }
        throw new ForbiddenException("Insufficient permissions");
    }

    /** Exige que o ator seja administrador da empresa operadora dona do condomínio (404 se nem enxerga o condomínio). */
    public Actor requireCompanyAdminOf(UUID condominiumId, String action) {
        Actor actor = currentActor();
        UUID companyId = condominiums.companyIdOf(condominiumId).orElse(null);
        boolean isAdmin = companyId != null && actor.memberships().stream()
                .anyMatch(m -> m.getRole() == Role.COMPANY_ADMIN && companyId.equals(m.getOperatorCompanyId()));
        if (isAdmin) {
            return actor;
        }
        audit.record(actor.userId(), null, condominiumId, action, "condominium", condominiumId.toString(),
                AuditOutcome.DENIED);
        if (levelFor(actor, condominiumId) == AccessLevel.NONE) {
            throw new NotFoundException("Condominium not found");
        }
        throw new ForbiddenException("Company administrator required");
    }

    /**
     * Exige que o ator seja morador do condomínio informado e devolve o cadastro ao qual está vinculado.
     * Quem não é morador ali recebe 404, como nos demais recursos de tenant.
     */
    public ResidentContext requireResident(UUID condominiumId, String action) {
        Actor actor = currentActor();
        return actor.memberships().stream()
                .filter(m -> m.getRole() == Role.RESIDENT && condominiumId.equals(m.getCondominiumId()))
                .findFirst()
                .map(m -> new ResidentContext(actor, m.getResidentId(), condominiumId))
                .orElseThrow(() -> {
                    audit.record(actor.userId(), null, condominiumId, action, "condominium",
                            condominiumId.toString(), AuditOutcome.DENIED);
                    return new NotFoundException("Condominium not found");
                });
    }

    /**
     * Exige que o usuário seja administrador da plataforma.
     *
     * @param action ação registrada na auditoria
     */
    public Actor requirePlatformAdmin(String action) {
        Actor actor = currentActor();
        if (actor.memberships().stream().noneMatch(m -> m.getRole() == Role.PLATFORM_ADMIN)) {
            audit.record(actor.userId(), null, null, action, null, null, AuditOutcome.DENIED);
            throw new ForbiddenException("Platform administrator required");
        }
        return actor;
    }

    /** Verifica papel de administrador da plataforma sem lançar exceção nem auditar. */
    public boolean isPlatformAdmin(Actor actor) {
        return actor.memberships().stream().anyMatch(m -> m.getRole() == Role.PLATFORM_ADMIN);
    }

    /** Empresas em que o ator é administrador. */
    public Set<UUID> companyIdsAdministeredBy(Actor actor) {
        return actor.memberships().stream()
                .filter(m -> m.getRole() == Role.COMPANY_ADMIN)
                .map(Membership::getOperatorCompanyId)
                .collect(Collectors.toSet());
    }

    /** Condomínios em que o ator atua como operador da central. */
    public Set<UUID> operatorCondominiumIds(Actor actor) {
        return actor.memberships().stream()
                .filter(m -> m.getRole() == Role.OPERATOR)
                .map(Membership::getCondominiumId)
                .collect(Collectors.toSet());
    }

    /** Condomínios atribuídos diretamente ao ator (gestor ou operador). */
    public Set<UUID> directCondominiumIds(Actor actor) {
        return actor.memberships().stream()
                .filter(m -> m.getRole() == Role.CONDO_MANAGER || m.getRole() == Role.OPERATOR)
                .map(Membership::getCondominiumId)
                .collect(Collectors.toSet());
    }

}
