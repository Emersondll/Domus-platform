package br.com.portaria.platform.access;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a visitantes, sempre escopado por condomínio. */
public interface VisitorRepository extends JpaRepository<Visitor, UUID> {

    Page<Visitor> findByCondominiumId(UUID condominiumId, Pageable pageable);
}
