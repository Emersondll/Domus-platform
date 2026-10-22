package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.identity.service.BootstrapAdmin;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.repository.MembershipRepository;
import br.com.domus.platform.identity.entity.Role;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Bootstrap do primeiro administrador. É teste unitário porque depende de "ainda não existe administrador", estado que
 * o banco compartilhado dos testes de integração não garante (a ordem dos testes muda entre máquinas).
 */
class BootstrapAdminTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final MembershipRepository memberships = mock(MembershipRepository.class);

    private BootstrapAdmin bootstrap(String subject, String email) {
        return new BootstrapAdmin(users, memberships, subject, email);
    }

    @Test
    void createsTheFirstPlatformAdministratorWhenNoneExists() {
        when(memberships.existsByRoleAndActiveTrue(Role.PLATFORM_ADMIN)).thenReturn(false);
        when(users.findByExternalSubject("sub-1")).thenReturn(Optional.empty());
        when(users.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        bootstrap("sub-1", "admin@example.invalid").run(new DefaultApplicationArguments());

        ArgumentCaptor<Membership> saved = ArgumentCaptor.forClass(Membership.class);
        verify(memberships).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.PLATFORM_ADMIN);
    }

    @Test
    void doesNothingOnceAnAdministratorExists() {
        when(memberships.existsByRoleAndActiveTrue(Role.PLATFORM_ADMIN)).thenReturn(true);

        bootstrap("sub-1", "admin@example.invalid").run(new DefaultApplicationArguments());

        verify(users, never()).save(any());
        verify(memberships, never()).save(any());
    }

    @Test
    void doesNothingWithoutConfiguration() {
        bootstrap("", "").run(new DefaultApplicationArguments());
        bootstrap("sub-1", "").run(new DefaultApplicationArguments());

        verify(memberships, never()).existsByRoleAndActiveTrue(any());
        verify(memberships, never()).save(any());
    }
}
