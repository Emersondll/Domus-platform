package br.com.portaria.platform.residents;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a moradores, sempre escopado por condomínio. */
public interface ResidentRepository extends JpaRepository<Resident, UUID> {

    Page<Resident> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<Resident> findByIdAndCondominiumId(UUID id, UUID condominiumId);
}
