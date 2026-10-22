package br.com.domus.platform.operations;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a turnos. */
public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByUserIdAndEndedAtIsNull(UUID userId);
}
