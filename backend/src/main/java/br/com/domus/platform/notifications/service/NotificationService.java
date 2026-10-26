package br.com.domus.platform.notifications.service;

import br.com.domus.platform.notifications.entity.Notification;
import br.com.domus.platform.notifications.entity.NotificationPreference;
import br.com.domus.platform.notifications.repository.NotificationPreferenceRepository;
import br.com.domus.platform.notifications.repository.NotificationRepository;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notificações dos moradores. O envio respeita as preferências de cada morador; o canal atual é a caixa
 * de entrada in-app. Push/e-mail/WhatsApp (API oficial) são pendências documentadas.
 */
@Service
public class NotificationService {

    public static final String PARCEL_RECEIVED = "PARCEL_RECEIVED";
    public static final String VISITOR_ARRIVED = "VISITOR_ARRIVED";
    /** Retorno do atendimento ao morador; não depende de preferência, é a resposta ao pedido dele. */
    /** Pedido da portaria para o morador autorizar um visitante; essencial, não depende de preferência. */
    public static final String VISIT_REQUEST = "VISIT_REQUEST";
    /** Comunicado do condomínio; essencial, não depende de preferência. */
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String DIRECT_MESSAGE = "DIRECT_MESSAGE";
    public static final String CALL_ASSIGNED = "CALL_ASSIGNED";
    public static final String CAMERA_RELEASED = "CAMERA_RELEASED";
    public static final String CALL_CLOSED = "CALL_CLOSED";

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final ResidentRepository residents;
    private final AccessService access;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, NotificationPreferenceRepository preferences,
                               ResidentRepository residents, AccessService access, Clock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.residents = residents;
        this.access = access;
        this.clock = clock;
    }

    /** Notifica os moradores com acesso ao app na unidade, respeitando a preferência de cada um. */
    @Transactional
    public void notifyUnit(UUID condominiumId, UUID unitId, String type, String title, String body) {
        Instant now = Instant.now(clock);
        for (UUID userId : residents.findUserIdsOfUnit(unitId, condominiumId)) {
            NotificationPreference pref = preferences.findById(userId).orElseGet(() -> new NotificationPreference(userId));
            boolean enabled = switch (type) {
                case PARCEL_RECEIVED -> pref.isParcelsEnabled();
                case VISITOR_ARRIVED -> pref.isVisitorArrivalEnabled();
                default -> true;
            };
            if (enabled) {
                notifications.save(new Notification(userId, condominiumId, type, title, body, now));
            }
        }
    }

    /** Notifica todos os moradores com acesso ao app no condomínio (comunicados). */
    @Transactional
    public void notifyCondominium(UUID condominiumId, String type, String title, String body) {
        Instant now = Instant.now(clock);
        for (UUID userId : residents.findUserIdsOfCondominium(condominiumId)) {
            notifications.save(new Notification(userId, condominiumId, type, title, body, now));
        }
    }

    /** Notifica um morador específico (resposta da portaria a uma mensagem). O texto da mensagem não é copiado. */
    @Transactional
    public void notifyUser(UUID condominiumId, UUID userId, String type, String title, String body) {
        notifications.save(new Notification(userId, condominiumId, type, title, body, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public Page<Notification> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "notification.list");
        return notifications.findByUserIdAndCondominiumId(me.actor().userId(), condominiumId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "notification.count");
        return notifications.countByUserIdAndCondominiumIdAndReadAtIsNull(me.actor().userId(), condominiumId);
    }

    @Transactional
    public Notification markRead(UUID condominiumId, UUID notificationId) {
        ResidentContext me = access.requireResident(condominiumId, "notification.read");
        Notification n = notifications.findByIdAndUserId(notificationId, me.actor().userId())
                .orElseThrow(() -> new NotFoundException("Notification not found"));
        n.markRead(Instant.now(clock));
        return n;
    }

    @Transactional(readOnly = true)
    public NotificationPreference preferences(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "preferences.read");
        return preferences.findById(me.actor().userId()).orElseGet(() -> new NotificationPreference(me.actor().userId()));
    }

    @Transactional
    public NotificationPreference updatePreferences(UUID condominiumId, boolean parcels, boolean visitorArrival) {
        ResidentContext me = access.requireResident(condominiumId, "preferences.update");
        NotificationPreference pref = preferences.findById(me.actor().userId())
                .orElseGet(() -> new NotificationPreference(me.actor().userId()));
        pref.update(parcels, visitorArrival);
        return preferences.save(pref);
    }
}
