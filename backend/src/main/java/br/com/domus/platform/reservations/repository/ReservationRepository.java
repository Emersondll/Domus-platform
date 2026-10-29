package br.com.domus.platform.reservations.repository;

import br.com.domus.platform.reservations.entity.Reservation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Acesso a reservas, sempre escopado por condomínio. */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Page<Reservation> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<Reservation> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    Optional<Reservation> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha até o fim da transação (a mudança de estado acontece uma única vez). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Reservation> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);
}
