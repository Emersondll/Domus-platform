package br.com.portaria.platform.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a usuários. */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByExternalSubject(String externalSubject);
}
