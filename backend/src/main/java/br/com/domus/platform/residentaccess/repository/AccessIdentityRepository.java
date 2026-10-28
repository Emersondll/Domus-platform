package br.com.domus.platform.residentaccess.repository;

import br.com.domus.platform.residentaccess.entity.AccessIdentity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Identidades de acesso do morador (cartão, QR, referência do equipamento), com a referência cifrada. */
public interface AccessIdentityRepository extends JpaRepository<AccessIdentity, UUID> {

    Optional<AccessIdentity> findByCondominiumIdAndRefHashAndActiveTrue(UUID condominiumId, String refHash);

    Optional<AccessIdentity> findByIdAndCondominiumIdAndResidentId(UUID id, UUID condominiumId, UUID residentId);

    List<AccessIdentity> findByCondominiumIdAndResidentIdOrderByCreatedAtDesc(UUID condominiumId, UUID residentId);

    void deleteByCondominiumIdAndResidentId(UUID condominiumId, UUID residentId);
}
