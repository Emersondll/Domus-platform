package br.com.portaria.platform.notifications;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a notificações, sempre escopado por usuário e condomínio. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdAndCondominiumId(UUID userId, UUID condominiumId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndCondominiumIdAndReadAtIsNull(UUID userId, UUID condominiumId);
}
