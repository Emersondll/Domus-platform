package br.com.portaria.platform.agent;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a conflitos de sincronização, sempre escopado por condomínio. */
public interface SyncConflictRepository extends JpaRepository<SyncConflict, UUID> {

    Page<SyncConflict> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<SyncConflict> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    long countByEventIdAndKind(UUID eventId, String kind);
}
