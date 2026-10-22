package br.com.domus.platform.operations;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a incidentes, sempre escopado por condomínio. */
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Page<Incident> findByCondominiumId(UUID condominiumId, Pageable pageable);

    long countByCondominiumIdAndStatus(UUID condominiumId, String status);

    Optional<Incident> findByIdAndCondominiumId(UUID id, UUID condominiumId);
}
