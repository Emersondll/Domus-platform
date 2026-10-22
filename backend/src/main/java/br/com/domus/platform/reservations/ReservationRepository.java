package br.com.domus.platform.reservations;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a reservas, sempre escopado por condomínio. */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Page<Reservation> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<Reservation> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    Optional<Reservation> findByIdAndCondominiumId(UUID id, UUID condominiumId);
}
