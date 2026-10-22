package br.com.domus.platform.identity.repository;

import br.com.domus.platform.identity.entity.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a usuários. */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByExternalSubject(String externalSubject);
}
