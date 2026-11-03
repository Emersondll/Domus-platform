package br.com.domus.platform.identity.service;

import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
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

    private final ObjectProvider<IdentityProvisioner> provisioner;
    private final MemberService members;
    private final AccessService access;

    /** Construtor para criação de um(a) provisioning service. */
    public ProvisioningService(ObjectProvider<IdentityProvisioner> provisioner, MemberService members, AccessService access) {
        this.provisioner = provisioner;
        this.members = members;
        this.access = access;
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
            return new Provisioned(link.apply(created.subject()), created.temporaryPassword());
        } catch (RuntimeException e) {
            idp.delete(created.subject());
            throw e;
        }
    }
}
