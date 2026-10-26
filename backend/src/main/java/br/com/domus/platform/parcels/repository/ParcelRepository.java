package br.com.domus.platform.parcels.repository;

import br.com.domus.platform.parcels.entity.Parcel;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a encomendas, sempre escopado por condomínio. */
public interface ParcelRepository extends JpaRepository<Parcel, UUID> {

    Page<Parcel> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<Parcel> findByCondominiumIdAndStatus(UUID condominiumId, String status, Pageable pageable);

    Page<Parcel> findByCondominiumIdAndUnitIdAndStatus(UUID condominiumId, UUID unitId, String status, Pageable pageable);

    Page<Parcel> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    Optional<Parcel> findByIdAndCondominiumId(UUID id, UUID condominiumId);
}
