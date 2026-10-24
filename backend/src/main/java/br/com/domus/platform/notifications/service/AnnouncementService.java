package br.com.domus.platform.notifications.service;

import br.com.domus.platform.notifications.entity.Announcement;
import br.com.domus.platform.notifications.repository.AnnouncementRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Comunicados do condomínio: a portaria ou o síndico publica; todos os moradores recebem e podem consultar. */
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcements;
    private final NotificationService notifications;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public AnnouncementService(AnnouncementRepository announcements, NotificationService notifications,
                               AccessService access, AuditService audit, Clock clock) {
        this.announcements = announcements;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Announcement publish(UUID condominiumId, String title, String body) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "announcement.publish");
        Announcement a = announcements.save(new Announcement(condominiumId, title, body, actor.userId(),
                Instant.now(clock)));
        audit.record(actor.userId(), null, condominiumId, "announcement.publish", "announcement",
                a.getId().toString(), AuditOutcome.SUCCESS);
        notifications.notifyCondominium(condominiumId, NotificationService.ANNOUNCEMENT, title, body);
        return a;
    }

    /** Operadores e síndico consultam o histórico publicado. */
    @Transactional(readOnly = true)
    public Page<Announcement> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "announcement.list");
        return announcements.findByCondominiumId(condominiumId, pageable);
    }

    /** O morador consulta os comunicados do seu condomínio. */
    @Transactional(readOnly = true)
    public Page<Announcement> listMine(UUID condominiumId, Pageable pageable) {
        access.requireResident(condominiumId, "announcement.list.self");
        return announcements.findByCondominiumId(condominiumId, pageable);
    }
}
