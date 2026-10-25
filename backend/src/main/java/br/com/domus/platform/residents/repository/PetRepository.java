package br.com.domus.platform.residents.repository;

import br.com.domus.platform.residents.entity.Pet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a animais de estimação, sempre escopado por condomínio. */
public interface PetRepository extends JpaRepository<Pet, UUID> {

    Page<Pet> findByCondominiumIdAndActiveTrue(UUID condominiumId, Pageable pageable);

    Page<Pet> findByCondominiumIdAndUnitIdAndActiveTrue(UUID condominiumId, UUID unitId, Pageable pageable);

    List<Pet> findByCondominiumIdAndUnitIdAndActiveTrueOrderByName(UUID condominiumId, UUID unitId);

    long countByCondominiumIdAndUnitIdAndActiveTrue(UUID condominiumId, UUID unitId);

    Optional<Pet> findByIdAndCondominiumId(UUID id, UUID condominiumId);
}
