package br.com.domus.platform.notifications.repository;

import br.com.domus.platform.notifications.entity.NotificationPreference;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a preferências de notificação. */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
}
