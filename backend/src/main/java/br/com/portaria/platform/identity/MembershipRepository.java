package br.com.portaria.platform.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a associações. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByUserIdAndActiveTrue(UUID userId);
}
