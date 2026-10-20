package br.com.portaria.platform.notifications;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a preferências de notificação. */
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
}
