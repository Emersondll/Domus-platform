package br.com.portaria.platform.commercial;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a unidades, sempre escopado por condomínio. */
public interface UnitRepository extends JpaRepository<Unit, UUID> {

    Page<Unit> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<Unit> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    boolean existsByBlockIdAndNumber(UUID blockId, String number);
}
