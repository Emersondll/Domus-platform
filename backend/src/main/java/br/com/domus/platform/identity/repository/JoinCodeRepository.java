package br.com.domus.platform.identity.repository;

import br.com.domus.platform.identity.entity.JoinCode;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a códigos de adesão. */
public interface JoinCodeRepository extends JpaRepository<JoinCode, UUID> {

    Optional<JoinCode> findByCode(String code);
}
