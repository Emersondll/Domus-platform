package br.com.domus.platform.notifications.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Preferências de privacidade/comunicação do morador. Por padrão tudo ligado; o morador pode desligar. */
@Entity
@Table(name = "notification_preference")
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "parcels_enabled", nullable = false)
    private boolean parcelsEnabled = true;

    @Column(name = "visitor_arrival_enabled", nullable = false)
    private boolean visitorArrivalEnabled = true;

    protected NotificationPreference() {
    }

    public NotificationPreference(UUID userId) {
        this.userId = userId;
    }

    public void update(boolean parcelsEnabled, boolean visitorArrivalEnabled) {
        this.parcelsEnabled = parcelsEnabled;
        this.visitorArrivalEnabled = visitorArrivalEnabled;
    }

    public boolean isParcelsEnabled() {
        return parcelsEnabled;
    }

    public boolean isVisitorArrivalEnabled() {
        return visitorArrivalEnabled;
    }
}
