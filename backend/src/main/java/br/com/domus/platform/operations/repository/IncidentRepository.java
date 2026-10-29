package br.com.domus.platform.operations.repository;

import br.com.domus.platform.operations.entity.Incident;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Acesso a incidentes, sempre escopado por condomínio. */
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Page<Incident> findByCondominiumId(UUID condominiumId, Pageable pageable);

    long countByCondominiumIdAndStatus(UUID condominiumId, String status);

    Optional<Incident> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha até o fim da transação (a mudança de estado acontece uma única vez). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Incident> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);
}
