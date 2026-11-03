package br.com.domus.platform.notifications.service;

import br.com.domus.platform.notifications.entity.Announcement;
import br.com.domus.platform.notifications.repository.AnnouncementRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.shared.error.NotFoundException;
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

    /** Construtor de AnnouncementService; recebe as dependências por injeção. */
    public AnnouncementService(AnnouncementRepository announcements, NotificationService notifications,
                               AccessService access, AuditService audit, Clock clock) {
        this.announcements = announcements;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Publica um comunicado e notifica os moradores.
     *
     * @param condominiumId condomínio
     * @param title título
     * @param body texto
     * @param expiresAt prazo para o morador ver o comunicado (nulo = sem prazo; deve estar no futuro)
     */
    @Transactional
    public Announcement publish(UUID condominiumId, String title, String body, Instant expiresAt) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "announcement.publish");
        requireValidExpiry(expiresAt);
        Announcement a = announcements.save(new Announcement(condominiumId, title, body, actor.userId(),
                Instant.now(clock)));
        a.expireAt(expiresAt);
        audit.record(actor.userId(), null, condominiumId, "announcement.publish", "announcement",
                a.getId().toString(), AuditOutcome.SUCCESS);
        notifications.notifyCondominium(condominiumId, NotificationService.ANNOUNCEMENT, title, body);
        return a;
    }

    /**
     * Apaga um comunicado publicado por engano. Os avisos que os moradores já receberam continuam com eles (não se desfaz uma
     * notificação entregue); o comunicado só deixa de aparecer na lista.
     */
    @Transactional
    public void remove(UUID condominiumId, UUID announcementId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "announcement.remove");
        announcements.delete(announcements.findByIdAndCondominiumId(announcementId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Announcement not found")));
        audit.record(actor.userId(), null, condominiumId, "announcement.remove", "announcement", announcementId.toString(),
                AuditOutcome.SUCCESS);
    }

    /**
     * Corrige o título e o texto de um comunicado. Os avisos que os moradores já receberam continuam com o texto antigo (não se
     * reescreve uma notificação entregue); a lista de comunicados passa a mostrar o texto corrigido. Não notifica de novo.
     */
    @Transactional
    public Announcement correct(UUID condominiumId, UUID announcementId, String title, String body, Instant expiresAt) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "announcement.update");
        Announcement a = announcements.findByIdAndCondominiumId(announcementId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Announcement not found"));
        requireValidExpiry(expiresAt);
        a.correct(title, body, expiresAt);
        audit.record(actor.userId(), null, condominiumId, "announcement.update", "announcement", announcementId.toString(),
                AuditOutcome.SUCCESS);
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
        return announcements.findActive(condominiumId, Instant.now(clock), pageable);
    }

    private void requireValidExpiry(Instant expiresAt) {
        Instant now = Instant.now(clock);
        if (expiresAt != null && (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(java.time.Duration.ofDays(366))))) {
            throw new InvalidRequestException("Expiry must be in the future and within one year");
        }
    }
}
