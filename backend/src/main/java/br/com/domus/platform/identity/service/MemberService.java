package br.com.domus.platform.identity.service;

import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.ResidentAccessRevoker;
import br.com.domus.platform.shared.ViewSessionRevoker;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.crypto.FieldCipher;
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
public class MemberService implements ResidentAccessRevoker {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final ResidentRepository residents;
    private final OperatorCompanyRepository companies;
    private final AccessService access;
    private final AuditService audit;
    private final FieldCipher cipher;
    private final ViewSessionRevoker viewSessions;

    /** Construtor para criação de um(a) member service. */
    public MemberService(AppUserRepository users, MembershipRepository memberships, ResidentRepository residents,
                         OperatorCompanyRepository companies, AccessService access, AuditService audit, FieldCipher cipher,
                         ViewSessionRevoker viewSessions) {
        this.cipher = cipher;
        this.viewSessions = viewSessions;
        this.users = users;
        this.memberships = memberships;
        this.residents = residents;
        this.companies = companies;
        this.access = access;
        this.audit = audit;
    }

    /**
     * Cadastra um administrador da empresa operadora.
     *
     * @param companyId empresa operadora
     * @param subject identificador do usuário no provedor de identidade
     * @param email e-mail
     * @param displayName nome de exibição
     */
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

    /**
     * @param membership associação de administrador
     * @param displayName nome de exibição do usuário
     */
    public record NamedMembership(Membership membership, String displayName) {
    }

    /**
     * Lista os administradores ativos de uma empresa operadora (só o administrador da plataforma).
     *
     * @param companyId empresa operadora
     */
    @Transactional(readOnly = true)
    public List<NamedMembership> listCompanyAdmins(UUID companyId) {
        access.requirePlatformAdmin("member.company-admin.list");
        if (!companies.existsById(companyId)) {
            throw new NotFoundException("Company not found");
        }
        return memberships.findByOperatorCompanyIdAndRoleAndActiveTrue(companyId, Role.COMPANY_ADMIN).stream()
                .map(m -> new NamedMembership(m, users.findById(m.getUserId()).map(AppUser::getDisplayName).orElse("")))
                .toList();
    }

    /**
     * Revoga um administrador da empresa. O próprio administrador da plataforma não perde acesso por isso: só o vínculo
     * com a empresa acaba.
     *
     * @param companyId empresa operadora
     * @param membershipId associação do administrador
     */
    @Transactional
    public void revokeCompanyAdmin(UUID companyId, UUID membershipId) {
        Actor actor = access.requirePlatformAdmin("member.company-admin.revoke");
        Membership m = memberships.findByIdAndOperatorCompanyIdAndRole(membershipId, companyId, Role.COMPANY_ADMIN)
                .filter(Membership::isActive)
                .orElseThrow(() -> new NotFoundException("Membership not found"));
        m.revoke();
        audit.record(actor.userId(), companyId, null, "member.company-admin.revoke", "membership", membershipId.toString(),
                AuditOutcome.SUCCESS);
    }

    /**
     * Cadastra um membro da equipe do condomínio (operador ou síndico).
     *
     * @param condominiumId condomínio
     * @param role papel
     * @param subject identificador do usuário no provedor de identidade
     * @param email e-mail
     * @param displayName nome de exibição
     */
    @Transactional
    public Membership addStaff(UUID condominiumId, Role role, String subject, String email, String displayName) {
        if (role != Role.OPERATOR && role != Role.CONDO_MANAGER) {
            throw new InvalidRequestException("Role must be OPERATOR or CONDO_MANAGER");
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

    /**
     * Vincula um morador a uma conta de acesso ao app.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @param subject identificador do usuário no provedor de identidade
     * @param email e-mail
     * @param displayName nome de exibição
     */
    @Transactional
    public Membership linkResident(UUID condominiumId, UUID residentId, String subject, String email, String displayName) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "member.resident.link");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        if (!resident.isActive()) {
            throw new ConflictException("Resident is inactive");
        }
        // decisão do titular: só adultos usam o app; dependentes ficam no cadastro, sem conta
        if (resident.getResidentType() == ResidentType.DEPENDENT) {
            throw new InvalidRequestException("Dependents do not have an app account; only adults use the app");
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

    /**
     * Lista a equipe do condomínio, visível a quem está nele (equipe de qualquer nível ou morador). O gestor vê também os
     * moradores vinculados e filtra por e-mail (índice cego).
     *
     * @param condominiumId condomínio
     * @param email e-mail
     * @return lista de resultados
     */
    @Transactional
    public List<NamedMembership> list(UUID condominiumId, String email) {
        Actor actor = access.currentActor();
        boolean manager = access.levelFor(actor, condominiumId).atLeast(AccessLevel.MANAGE);
        boolean staff = access.levelFor(actor, condominiumId).atLeast(AccessLevel.READ);
        boolean resident = actor.memberships().stream()
                .anyMatch(m -> m.getRole() == Role.RESIDENT && condominiumId.equals(m.getCondominiumId()));
        if (!manager && !staff && !resident) {
            audit.record(actor.userId(), null, condominiumId, "member.list", "condominium", condominiumId.toString(),
                    AuditOutcome.DENIED);
            throw new NotFoundException("Condominium not found");
        }
        // Por segurança, quem está no condomínio (equipe de qualquer nível ou morador) vê quem é a equipe. Só o gestor
        // enxerga também os moradores vinculados e pode filtrar por e-mail.
        List<Membership> found = manager ? listMemberships(condominiumId, email)
                : memberships.findByCondominiumIdAndActiveTrue(condominiumId).stream()
                        .filter(m -> m.getRole() != Role.RESIDENT).toList();
        List<NamedMembership> result = withNames(found);
        // a listagem devolve nomes de pessoas: leitura de dado pessoal fica na auditoria (por isso não é readOnly)
        audit.record(actor.userId(), null, condominiumId, "member.list", "membership", null, AuditOutcome.SUCCESS);
        return result;
    }

    private List<NamedMembership> withNames(List<Membership> found) {
        java.util.Map<UUID, String> names = new java.util.HashMap<>();
        users.findAllById(found.stream().map(Membership::getUserId).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getDisplayName()));
        return found.stream().map(m -> new NamedMembership(m, names.getOrDefault(m.getUserId(), ""))).toList();
    }

    private List<Membership> listMemberships(UUID condominiumId, String email) {
        List<Membership> all = memberships.findByCondominiumIdAndActiveTrue(condominiumId);
        if (email == null || email.isBlank()) {
            return all;
        }
        // busca por igualdade pelo índice cego; o e-mail nunca é comparado em texto
        UUID userId = users.findByEmailHash(cipher.emailIndex(email)).map(AppUser::getId).orElse(null);
        return all.stream().filter(m -> m.getUserId().equals(userId)).toList();
    }

    /**
     * Revoga uma associação do condomínio.
     *
     * @param condominiumId condomínio
     * @param membershipId associação
     */
    @Transactional
    public void revoke(UUID condominiumId, UUID membershipId) {
        Actor actor = access.requireCompanyAdminOf(condominiumId, "member.revoke");
        Membership m = memberships.findByIdAndCondominiumId(membershipId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Membership not found"));
        m.revoke();
        // O vínculo acabou: o vídeo que essa pessoa já tinha aberto também (o token sozinho não dá mais acesso).
        viewSessions.revokeForUser(m.getUserId(), condominiumId, "MEMBERSHIP_REVOKED");
        audit.record(actor.userId(), null, condominiumId, "member.revoke", "membership", membershipId.toString(),
                AuditOutcome.SUCCESS);
    }

    /** O cadastro foi desativado (mudou-se, saiu): sem vínculo ele não chama a central, não convida nem vê câmeras. */
    @Override
    @Transactional
    public int revokeResidentAccess(UUID actorUserId, UUID condominiumId, UUID residentId) {
        int revoked = 0;
        for (Membership m : memberships.findByResidentId(residentId)) {
            if (m.isActive() && condominiumId.equals(m.getCondominiumId())) {
                m.revoke();
                viewSessions.revokeForUser(m.getUserId(), condominiumId, "RESIDENT_DEACTIVATED");
                audit.record(actorUserId, null, condominiumId, "member.revoke", "membership", m.getId().toString(),
                        AuditOutcome.SUCCESS);
                revoked++;
            }
        }
        return revoked;
    }

    AppUser upsertUser(String subject, String email, String displayName) {
        AppUser existing = users.findByExternalSubject(subject).orElse(null);
        if (existing != null) {
            if (!existing.isActive()) {
                throw new ConflictException("User is inactive");
            }
            return existing;
        }
        String emailHash = cipher.emailIndex(email);
        if (users.findByEmailHash(emailHash).isPresent()) {
            throw new ConflictException("E-mail already belongs to another account");
        }
        return users.save(new AppUser(subject, email, displayName, emailHash));
    }
}
