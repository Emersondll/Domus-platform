package br.com.domus.platform.notifications.repository;

import br.com.domus.platform.notifications.entity.Announcement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a comunicados, sempre escopado por condomínio. */
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    Optional<Announcement> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    Page<Announcement> findByCondominiumId(UUID condominiumId, Pageable pageable);
}
