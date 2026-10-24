package br.com.domus.platform.operations.repository;

import br.com.domus.platform.operations.entity.ShiftLogEntry;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Livro de turno, sempre escopado por condomínio. */
public interface ShiftLogEntryRepository extends JpaRepository<ShiftLogEntry, UUID> {

    Page<ShiftLogEntry> findByCondominiumId(UUID condominiumId, Pageable pageable);
}
