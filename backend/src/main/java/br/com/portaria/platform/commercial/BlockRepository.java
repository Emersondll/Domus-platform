package br.com.portaria.platform.commercial;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a blocos, sempre escopado por condomínio. */
public interface BlockRepository extends JpaRepository<Block, UUID> {

    Page<Block> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<Block> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
