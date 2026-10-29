package br.com.domus.platform.parcels.repository;

import br.com.domus.platform.parcels.entity.Parcel;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Acesso a encomendas, sempre escopado por condomínio. */
public interface ParcelRepository extends JpaRepository<Parcel, UUID> {

    Page<Parcel> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<Parcel> findByCondominiumIdAndStatus(UUID condominiumId, String status, Pageable pageable);

    Page<Parcel> findByCondominiumIdAndUnitIdAndStatus(UUID condominiumId, UUID unitId, String status, Pageable pageable);

    Page<Parcel> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    Optional<Parcel> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha até o fim da transação (a retirada acontece uma única vez). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Parcel> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);
}
