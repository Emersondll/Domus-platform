package br.com.domus.platform.reservations.repository;

import br.com.domus.platform.reservations.entity.CommonArea;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a áreas comuns, sempre escopado por condomínio. */
public interface CommonAreaRepository extends JpaRepository<CommonArea, UUID> {

    Page<CommonArea> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<CommonArea> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
