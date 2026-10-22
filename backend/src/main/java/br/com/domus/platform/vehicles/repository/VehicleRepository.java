package br.com.domus.platform.vehicles.repository;

import br.com.domus.platform.vehicles.entity.Vehicle;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a veículos, sempre escopado por condomínio. */
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    Page<Vehicle> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<Vehicle> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    boolean existsByCondominiumIdAndPlateHash(UUID condominiumId, String plateHash);
}
