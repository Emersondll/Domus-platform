package br.com.domus.platform.identity;

import br.com.domus.platform.commercial.OperatorCompanyRepository;
import br.com.domus.platform.residents.Resident;
import br.com.domus.platform.residents.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisionamento de acessos: quem entra no sistema e com qual papel. A conta (login, senha, MFA) vive no provedor de
 * identidade; aqui só se vincula o {@code sub} do token a um papel e escopo. Regras: administradores de empresa são
 * criados pela plataforma; operadores e síndicos, pelo administrador da empresa; contas de morador, pela gestão do
 * condomínio. Revogar uma associação tem efeito imediato (o papel nunca vem do token).
 */
@Service
public class MemberService {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final ResidentRepository residents;
    private final OperatorCompanyRepository companies;
    private final AccessService access;
    private final AuditService audit;

    public MemberService(AppUserRepository users, MembershipRepository memberships, ResidentRepository residents,
                         OperatorCompanyRepository companies, AccessService access, AuditService audit) {
        this.users = users;
        this.memberships = memberships;
        this.residents = residents;
        this.companies = companies;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Membership addCompanyAdmin(UUID companyId, String subject, String email, String displayName) {
        Actor actor = access.requirePlatformAdmin("member.company-admin.add");
        if (!companies.existsById(companyId)) {
            throw new NotFoundException("Company not found");
        }
        AppUser user = upsertUser(subject, email, displayName);
        if (memberships.existsByUserIdAndRoleAndOperatorCompanyIdAndActiveTrue(user.getId(), Role.COMPANY_ADMIN, companyId)) {
            throw new ConflictException("User is already an administrator of this company");
        }
        Membership m = memberships.save(new Membership(user.getId(), Role.COMPANY_ADMIN, companyId, null));
        audit.record(actor.userId(), companyId, null, "member.company-admin.add", "membership", m.getId().toString(),
                AuditOutcome.SUCCESS);
        return m;
    }

    @Transactional
    public Membership addStaff(UUID condominiumId, Role role, String subject, String email, String displayName) {
        if (role != Role.OPERATOR && role != Role.CONDO_MANAGER) {
            throw new IllegalArgumentException("Role must be OPERATOR or CONDO_MANAGER");
        }
        Actor actor = access.requireCompanyAdminOf(condominiumId, "member.staff.add");
        AppUser user = upsertUser(subject, email, displayName);
        if (memberships.existsByUserIdAndRoleAndCondominiumIdAndActiveTrue(user.getId(), role, condominiumId)) {
            throw new ConflictException("User already has this role in the condominium");
        }
        Membership m = memberships.save(new Membership(user.getId(), role, null, condominiumId));
        audit.record(actor.userId(), null, condominiumId, "member.staff.add." + role, "membership",
                m.getId().toString(), AuditOutcome.SUCCESS);
        return m;
    }

    @Transactional
    public Membership linkResident(UUID condominiumId, UUID residentId, String subject, String email, String displayName) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "member.resident.link");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        if (!resident.isActive()) {
            throw new ConflictException("Resident is inactive");
        }
        if (memberships.findByResidentId(residentId).stream().anyMatch(Membership::isActive)) {
            throw new ConflictException("Resident already has an app account");
        }
        AppUser user = upsertUser(subject, email, displayName);
        Membership m = memberships.save(Membership.forResident(user.getId(), condominiumId, residentId));
        audit.record(actor.userId(), null, condominiumId, "member.resident.link", "membership", m.getId().toString(),
                AuditOutcome.SUCCESS);
        return m;
    }

    @Transactional(readOnly = true)
    public List<Membership> list(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.MANAGE, "member.list");
        return memberships.findByCondominiumIdAndActiveTrue(condominiumId);
    }

    @Transactional
    public void revoke(UUID condominiumId, UUID membershipId) {
        Actor actor = access.requireCompanyAdminOf(condominiumId, "member.revoke");
        Membership m = memberships.findByIdAndCondominiumId(membershipId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Membership not found"));
        m.revoke();
        audit.record(actor.userId(), null, condominiumId, "member.revoke", "membership", membershipId.toString(),
                AuditOutcome.SUCCESS);
    }

    private AppUser upsertUser(String subject, String email, String displayName) {
        AppUser existing = users.findByExternalSubject(subject).orElse(null);
        if (existing != null) {
            if (!existing.isActive()) {
                throw new ConflictException("User is inactive");
            }
            return existing;
        }
        return users.save(new AppUser(subject, email, displayName));
    }
}
