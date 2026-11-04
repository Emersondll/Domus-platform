package br.com.domus.platform.identity.service;

import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.shared.tenant.AccessService;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Cria a conta de uma pessoa da equipe no provedor de identidade e já a vincula ao papel, sem copiar o {@code sub} à
 * mão. A autorização é conferida ANTES de falar com o provedor; se o vínculo local falhar, a conta recém-criada é
 * removida (a criação no provedor não participa da transação do banco).
 */
@Service
public class ProvisioningService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProvisioningService.class);

    private final ObjectProvider<IdentityProvisioner> provisioner;
    private final MemberService members;
    private final AccessService access;
    private final ProvisionedAccounts provisioned;
    private final MembershipRepository memberships;
    private final AuditService audit;

    /** Construtor para criação de um(a) provisioning service. */
    public ProvisioningService(ObjectProvider<IdentityProvisioner> provisioner, MemberService members, AccessService access,
                               ProvisionedAccounts provisioned, MembershipRepository memberships, AuditService audit) {
        this.provisioner = provisioner;
        this.members = members;
        this.access = access;
        this.provisioned = provisioned;
        this.memberships = memberships;
        this.audit = audit;
    }

    /** Resultado: a associação criada e a senha provisória (mostrada uma única vez). */
    public record Provisioned(Membership membership, String temporaryPassword) {
    }

    /** Indica se a criação de contas pelo provedor está ligada nesta instalação. */
    public boolean enabled() {
        return provisioner.getIfAvailable() != null;
    }

    /**
     * Cria a conta de um operador ou síndico do condomínio.
     *
     * @param condominiumId condomínio
     * @param role OPERATOR ou CONDO_MANAGER
     * @param email e-mail (vira o nome de usuário)
     * @param displayName nome de exibição
     */
    public Provisioned provisionStaff(UUID condominiumId, Role role, String email, String displayName) {
        if (role != Role.OPERATOR && role != Role.CONDO_MANAGER) {
            throw new InvalidRequestException("Role must be OPERATOR or CONDO_MANAGER");
        }
        access.requireCompanyAdminOf(condominiumId, "member.staff.provision");
        return provision(role, email, displayName,
                subject -> members.addStaff(condominiumId, role, subject, email, displayName));
    }

    /**
     * Cria a conta de um administrador da empresa operadora (só a plataforma).
     *
     * @param companyId empresa operadora
     * @param email e-mail
     * @param displayName nome de exibição
     */
    public Provisioned provisionCompanyAdmin(UUID companyId, String email, String displayName) {
        access.requirePlatformAdmin("member.company-admin.provision");
        return provision(Role.COMPANY_ADMIN, email, displayName,
                subject -> members.addCompanyAdmin(companyId, subject, email, displayName));
    }

    private Provisioned provision(Role role, String email, String displayName,
                                  java.util.function.Function<String, Membership> link) {
        IdentityProvisioner idp = provisioner.getIfAvailable();
        if (idp == null) {
            throw new ConflictException("Account creation in the identity provider is not enabled");
        }
        IdentityProvisioner.Created created = idp.create(email, displayName, AccessService.providerRole(role));
        try {
            Membership linked = link.apply(created.subject());
            try {
                provisioned.record(created.subject());
            } catch (RuntimeException e) {
                // o vínculo já foi gravado: não desfazer. Só perde a reposição de senha e a limpeza automática dessa conta.
                log.error("Could not record provisioned identity account {}", created.subject(), e);
            }
            return new Provisioned(linked, created.temporaryPassword());
        } catch (RuntimeException e) {
            idp.delete(created.subject());
            throw e;
        }
    }

    /**
     * Gera uma nova senha provisória para quem perdeu a primeira (mostrada uma única vez, troca obrigatória no próximo acesso).
     * Só vale para contas criadas pela plataforma e que não tenham nenhum acesso fora do condomínio.
     *
     * @param condominiumId condomínio
     * @param membershipId associação da pessoa da equipe
     */
    public String resetStaffPassword(UUID condominiumId, UUID membershipId) {
        Actor actor = access.requireCompanyAdminOf(condominiumId, "member.staff.reset-password");
        Membership m = memberships.findByIdAndCondominiumId(membershipId, condominiumId).filter(Membership::isActive)
                .filter(x -> x.getRole() == Role.OPERATOR || x.getRole() == Role.CONDO_MANAGER)
                .orElseThrow(() -> new NotFoundException("Membership not found"));
        return reset(actor, m, null, condominiumId,
                x -> condominiumId.equals(x.getCondominiumId()) && x.getOperatorCompanyId() == null);
    }

    /**
     * Mesma reposição de senha para um administrador de empresa (só a plataforma).
     *
     * @param companyId empresa operadora
     * @param membershipId associação do administrador
     */
    public String resetCompanyAdminPassword(UUID companyId, UUID membershipId) {
        Actor actor = access.requirePlatformAdmin("member.company-admin.reset-password");
        Membership m = memberships.findByIdAndOperatorCompanyIdAndRole(membershipId, companyId, Role.COMPANY_ADMIN)
                .filter(Membership::isActive).orElseThrow(() -> new NotFoundException("Membership not found"));
        return reset(actor, m, companyId, null,
                x -> companyId.equals(x.getOperatorCompanyId()) && x.getRole() == Role.COMPANY_ADMIN);
    }

    private String reset(Actor actor, Membership m, UUID companyId, UUID condominiumId,
                         java.util.function.Predicate<Membership> scope) {
        IdentityProvisioner idp = provisioner.getIfAvailable();
        if (idp == null) {
            throw new ConflictException("Account creation in the identity provider is not enabled");
        }
        String subject = provisioned.subjectForReset(m.getUserId(), scope);
        String password = idp.resetPassword(subject);
        provisioned.auditPasswordReset(actor.userId(), companyId, condominiumId, m.getId());
        return password;
    }
}
