package br.com.portaria.platform.shared.tenant;

import br.com.portaria.platform.identity.Membership;
import java.util.List;
import java.util.UUID;

/** Usuário autenticado já resolvido no banco, com suas associações ativas. */
public record Actor(UUID userId, List<Membership> memberships) {
}
