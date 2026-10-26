package br.com.domus.platform.residentaccess.repository;

import br.com.domus.platform.residentaccess.entity.ResidentPhoto;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResidentPhotoRepository extends JpaRepository<ResidentPhoto, UUID> {

    Optional<ResidentPhoto> findByResidentIdAndCondominiumId(UUID residentId, UUID condominiumId);

    void deleteByResidentId(UUID residentId);
}
