package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.portaria.platform.identity.AppUserRepository;
import br.com.portaria.platform.identity.BootstrapAdmin;
import br.com.portaria.platform.identity.MembershipRepository;
import br.com.portaria.platform.identity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

/** Bootstrap do primeiro administrador da plataforma: cria uma vez e não duplica. */
@SpringBootTest(properties = {
        "portaria.bootstrap.platform-admin-subject=bootstrap-admin-subject",
        "portaria.bootstrap.platform-admin-email=bootstrap@example.invalid"})
class BootstrapAdminIT extends AbstractIntegrationTest {

    @Autowired BootstrapAdmin bootstrap;
    @Autowired AppUserRepository users;
    @Autowired MembershipRepository memberships;

    @Test
    void createsTheFirstPlatformAdministratorOnlyOnce() throws Exception {
        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());
        var user = users.findByExternalSubject("bootstrap-admin-subject").orElseThrow();
        assertThat(memberships.findByUserIdAndActiveTrue(user.getId()))
                .filteredOn(m -> m.getRole() == Role.PLATFORM_ADMIN).hasSize(1);
        assertThat(memberships.existsByRoleAndActiveTrue(Role.PLATFORM_ADMIN)).isTrue();
    }
}
