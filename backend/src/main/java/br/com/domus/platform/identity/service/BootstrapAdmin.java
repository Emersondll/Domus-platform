package br.com.domus.platform.identity.service;

import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.shared.crypto.FieldCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria o primeiro administrador da plataforma quando ainda não existe nenhum (instalação nova), a partir do
 * {@code sub} da conta criada no provedor de identidade. Idempotente: depois que existe um administrador, é ignorado.
 * Configuração: {@code BOOTSTRAP_PLATFORM_ADMIN_SUBJECT} e {@code BOOTSTRAP_PLATFORM_ADMIN_EMAIL}.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final String subject;
    private final String email;
    private final FieldCipher cipher;

    public BootstrapAdmin(AppUserRepository users, MembershipRepository memberships, FieldCipher cipher,
            @Value("${portaria.bootstrap.platform-admin-subject:}") String subject,
            @Value("${portaria.bootstrap.platform-admin-email:}") String email) {
        this.users = users;
        this.memberships = memberships;
        this.cipher = cipher;
        this.subject = subject;
        this.email = email;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (subject.isBlank() || email.isBlank()) {
            return;
        }
        if (memberships.existsByRoleAndActiveTrue(Role.PLATFORM_ADMIN)) {
            return;
        }
        AppUser user = users.findByExternalSubject(subject)
                .orElseGet(() -> users.save(new AppUser(subject, email, "Administrador da plataforma", cipher.emailIndex(email))));
        memberships.save(new Membership(user.getId(), Role.PLATFORM_ADMIN, null, null));
        log.warn("Bootstrap: platform administrator created for subject {}. Remove the BOOTSTRAP_* variables now.", subject);
    }
}
