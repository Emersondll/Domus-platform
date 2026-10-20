package br.com.portaria.platform.access;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso ao histórico de tentativas. */
public interface AccessAttemptRepository extends JpaRepository<AccessAttempt, UUID> {

    Page<AccessAttempt> findByCondominiumId(UUID condominiumId, Pageable pageable);
}
