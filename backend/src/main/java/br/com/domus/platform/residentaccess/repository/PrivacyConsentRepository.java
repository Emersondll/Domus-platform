package br.com.domus.platform.residentaccess.repository;

import br.com.domus.platform.residentaccess.entity.PrivacyConsent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Consentimentos registrados (texto, versão e revogação). */
public interface PrivacyConsentRepository extends JpaRepository<PrivacyConsent, UUID> {

    Optional<PrivacyConsent> findFirstByResidentIdAndPurposeAndRevokedAtIsNull(UUID residentId, String purpose);

    Optional<PrivacyConsent> findByIdAndResidentId(UUID id, UUID residentId);

    List<PrivacyConsent> findByResidentIdOrderByGrantedAtDesc(UUID residentId);
}
