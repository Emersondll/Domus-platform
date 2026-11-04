package br.com.domus.platform.identity.service;

import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.ProvisionedAccount;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.repository.ProvisionedAccountRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controle das contas que a própria API criou no provedor de identidade. Só elas podem ter a senha reposta pela tela e só elas
 * são apagadas do provedor quando a pessoa fica sem nenhuma associação ativa (assim o mesmo e-mail pode ser cadastrado de novo);
 * contas criadas à mão no Keycloak nunca são tocadas.
 */
@Service
public class ProvisionedAccounts {

    private static final Logger log = LoggerFactory.getLogger(ProvisionedAccounts.class);

    private final ProvisionedAccountRepository accounts;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final ObjectProvider<IdentityProvisioner> provisioner;
    private final AuditService audit;

    /** Construtor para criação de um(a) provisioned accounts. */
    public ProvisionedAccounts(ProvisionedAccountRepository accounts, AppUserRepository users, MembershipRepository memberships,
                               ObjectProvider<IdentityProvisioner> provisioner, AuditService audit) {
        this.audit = audit;
        this.accounts = accounts;
        this.users = users;
        this.memberships = memberships;
        this.provisioner = provisioner;
    }

    /** Anota que a conta foi criada pela API. */
    @Transactional
    public void record(String subject) {
        accounts.save(new ProvisionedAccount(subject));
    }

    /**
     * Depois de revogar uma associação: se a pessoa ficou sem nenhuma associação ativa e a conta foi criada pela API, anonimiza o
     * usuário local (libera o e-mail) e, só se a transação confirmar, apaga a conta no provedor. Qualquer falha ao apagar vai para o
     * log e não desfaz a revogação, que já é o que corta o acesso.
     */
    @Transactional
    public void releaseIfOrphan(UUID userId) {
        IdentityProvisioner idp = provisioner.getIfAvailable();
        AppUser user = users.findById(userId).orElse(null);
        if (idp == null || user == null || !accounts.existsById(user.getExternalSubject())
                || !memberships.findByUserIdAndActiveTrue(userId).isEmpty()) {
            return;
        }
        String subject = user.getExternalSubject();
        user.anonymize();
        accounts.deleteById(subject);
        Runnable delete = () -> {
            try {
                idp.delete(subject);
            } catch (RuntimeException e) {
                log.error("Could not delete identity account {} after the last membership was revoked; remove it manually", subject);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    /**
     * Confere se a senha desta pessoa pode ser reposta por quem pede: a conta foi criada pela API e TODAS as associações ativas
     * dela cabem no escopo de quem pede. Sem a segunda regra, o administrador de uma empresa trocaria a senha (e entraria na conta)
     * de alguém que também tem acesso em outra empresa ou é administrador da plataforma.
     *
     * @param userId usuário local
     * @param withinScope diz se uma associação ativa está no escopo de quem pede
     * @return o {@code sub} da conta
     */
    @Transactional(readOnly = true)
    public String subjectForReset(UUID userId, Predicate<Membership> withinScope) {
        AppUser user = users.findById(userId).filter(AppUser::isActive)
                .orElseThrow(() -> new ConflictException("Account is not available"));
        List<Membership> active = memberships.findByUserIdAndActiveTrue(userId);
        if (!accounts.existsById(user.getExternalSubject())) {
            throw new ConflictException("This account was not created by the platform; reset the password in the identity provider");
        }
        if (!active.stream().allMatch(withinScope)) {
            throw new ConflictException("This account also has access outside your scope; reset the password in the identity provider");
        }
        return user.getExternalSubject();
    }

    /** Registra na auditoria (sem a senha) que uma senha provisória nova foi gerada. */
    @Transactional
    public void auditPasswordReset(UUID actorUserId, UUID companyId, UUID condominiumId, UUID membershipId) {
        audit.record(actorUserId, companyId, condominiumId, "member.reset-password", "membership", membershipId.toString(),
                AuditOutcome.SUCCESS);
    }
}
