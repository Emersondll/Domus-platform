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

    /** Construtor para criação de um(a) notification preference. */
    public NotificationPreference(UUID userId) {
        this.userId = userId;
    }

    /**
     * Atualiza as preferências de assunto.
     *
     * @param parcelsEnabled parcels enabled
     * @param visitorArrivalEnabled visitor arrival enabled
     */
    public void update(boolean parcelsEnabled, boolean visitorArrivalEnabled) {
        this.parcelsEnabled = parcelsEnabled;
        this.visitorArrivalEnabled = visitorArrivalEnabled;
    }

    /** Indica se parcels enabled. */
    public boolean isParcelsEnabled() {
        return parcelsEnabled;
    }

    /** Indica se visitor arrival enabled. */
    public boolean isVisitorArrivalEnabled() {
        return visitorArrivalEnabled;
    }
}
