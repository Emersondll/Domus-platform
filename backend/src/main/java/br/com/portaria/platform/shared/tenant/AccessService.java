package br.com.portaria.platform.shared.tenant;

import br.com.portaria.platform.identity.AppUser;
import br.com.portaria.platform.identity.AppUserRepository;
import br.com.portaria.platform.identity.Membership;
import br.com.portaria.platform.identity.MembershipRepository;
import br.com.portaria.platform.identity.Role;
import br.com.portaria.platform.shared.audit.AuditOutcome;
import br.com.portaria.platform.shared.audit.AuditService;
import br.com.portaria.platform.shared.error.ForbiddenException;
import br.com.portaria.platform.shared.error.NotFoundException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    public AccessService(AppUserRepository users, MembershipRepository memberships,
                         CondominiumLookup condominiums, AuditService audit) {
        this.users = users;
        this.memberships = memberships;
        this.condominiums = condominiums;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Actor currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("Authentication required");
        }
        AppUser user = users.findByExternalSubject(jwt.getSubject())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new ForbiddenException("User is not registered or is inactive"));
        return new Actor(user.getId(), memberships.findByUserIdAndActiveTrue(user.getId()));
    }

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
                    if (companyId != null && companyId.equals(m.getOperatorCompanyId())) {
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

    /** Condomínios atribuídos diretamente ao ator (gestor ou operador). */
    public Set<UUID> directCondominiumIds(Actor actor) {
        return actor.memberships().stream()
                .filter(m -> m.getRole() == Role.CONDO_MANAGER || m.getRole() == Role.OPERATOR)
                .map(Membership::getCondominiumId)
                .collect(Collectors.toSet());
    }

}
