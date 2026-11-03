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

    /** Comunicados ainda dentro do prazo (sem prazo ou com prazo no futuro): o que o morador enxerga. */
    @org.springframework.data.jpa.repository.Query("select a from Announcement a where a.condominiumId = :condominiumId "
            + "and (a.expiresAt is null or a.expiresAt > :now)")
    Page<Announcement> findActive(@org.springframework.data.repository.query.Param("condominiumId") UUID condominiumId,
                                  @org.springframework.data.repository.query.Param("now") java.time.Instant now, Pageable pageable);
}
