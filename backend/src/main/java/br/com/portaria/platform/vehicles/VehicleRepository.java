package br.com.portaria.platform.vehicles;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a veículos, sempre escopado por condomínio. */
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    Page<Vehicle> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<Vehicle> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    boolean existsByCondominiumIdAndPlate(UUID condominiumId, String plate);
}
