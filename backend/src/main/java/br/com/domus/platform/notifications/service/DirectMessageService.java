package br.com.domus.platform.notifications.service;

import br.com.domus.platform.identity.entity.AppUser;
import br.com.domus.platform.identity.repository.AppUserRepository;
import br.com.domus.platform.notifications.entity.DirectMessage;
import br.com.domus.platform.notifications.repository.DirectMessageRepository;
import br.com.domus.platform.notifications.repository.DirectMessageRepository.ThreadRow;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.error.TooManyRequestsException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mensagens individuais entre o morador e a portaria: uma conversa por morador e condomínio. O morador só enxerga a
 * própria conversa; a portaria (OPERATE) enxerga as conversas do condomínio. O texto é cifrado e não vai para a
 * auditoria nem para a notificação.
 */
@Service
public class DirectMessageService {

    /** Limite de mensagens do morador por janela, para evitar inundar a portaria. */
    static final int RESIDENT_LIMIT = 10;
    static final Duration RESIDENT_WINDOW = Duration.ofMinutes(1);

    private final DirectMessageRepository messages;
    private final ResidentRepository residents;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    /** Construtor de DirectMessageService; recebe as dependências por injeção. */
    public DirectMessageService(DirectMessageRepository messages, ResidentRepository residents,
                                AppUserRepository users, NotificationService notifications, AccessService access,
                                AuditService audit, Clock clock) {
        this.messages = messages;
        this.residents = residents;
        this.users = users;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /** Resumo de conversa para a lista da portaria. */
    public record ThreadSummary(UUID residentUserId, String residentName, Instant lastAt, long unread) {
    }

    /**
     * Envia uma mensagem do morador autenticado para a portaria.
     *
     * @param condominiumId condomínio
     * @param text texto
     */
    @Transactional
    public DirectMessage sendAsResident(UUID condominiumId, String text) {
        ResidentContext me = access.requireResident(condominiumId, "message.send.resident");
        UUID userId = me.actor().userId();
        Instant now = Instant.now(clock);
        if (messages.countByCondominiumIdAndResidentUserIdAndFromResidentAndCreatedAtAfter(
                condominiumId, userId, true, now.minus(RESIDENT_WINDOW)) >= RESIDENT_LIMIT) {
            audit.record(userId, null, condominiumId, "message.send.resident", "direct_message", null,
                    AuditOutcome.DENIED);
            throw new TooManyRequestsException("Too many messages; try again in a minute");
        }
        DirectMessage m = messages.save(new DirectMessage(condominiumId, userId, userId, true, text.trim(), now));
        audit.record(userId, null, condominiumId, "message.send.resident", "direct_message",
                m.getId().toString(), AuditOutcome.SUCCESS);
        return m;
    }

    /** O morador lê a própria conversa; as respostas da portaria passam a constar como lidas. */
    @Transactional
    public Page<DirectMessage> listMine(UUID condominiumId, Pageable pageable) {
        ResidentContext me = access.requireResident(condominiumId, "message.list.resident");
        UUID userId = me.actor().userId();
        Instant now = Instant.now(clock);
        messages.findByCondominiumIdAndResidentUserIdAndFromResidentAndReadAtIsNull(condominiumId, userId, false)
                .forEach(m -> m.markRead(now));
        return messages.findByCondominiumIdAndResidentUserId(condominiumId, userId, pageable);
    }

    /** Quantas respostas da portaria o morador ainda não leu. */
    @Transactional(readOnly = true)
    public long unreadForResident(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "message.unread.resident");
        return messages.findByCondominiumIdAndResidentUserIdAndFromResidentAndReadAtIsNull(
                condominiumId, me.actor().userId(), false).size();
    }

    /**
     * Lista as conversas da portaria, uma por morador.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    @Transactional(readOnly = true)
    public List<ThreadSummary> threads(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.OPERATE, "message.threads");
        List<ThreadRow> rows = messages.threads(condominiumId);
        Map<UUID, String> names = users.findAllById(rows.stream().map(ThreadRow::getResidentUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName, (a, b) -> a));
        return rows.stream().map(r -> new ThreadSummary(r.getResidentUserId(),
                names.getOrDefault(r.getResidentUserId(), "Morador"), r.getLastAt(), r.getUnread())).toList();
    }

    /** A portaria abre a conversa de um morador; as mensagens do morador passam a constar como lidas. */
    @Transactional
    public Page<DirectMessage> openThread(UUID condominiumId, UUID residentUserId, Pageable pageable) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "message.thread.read");
        requireResidentUser(condominiumId, residentUserId);
        Instant now = Instant.now(clock);
        messages.findByCondominiumIdAndResidentUserIdAndFromResidentAndReadAtIsNull(
                condominiumId, residentUserId, true).forEach(m -> m.markRead(now));
        audit.record(actor.userId(), null, condominiumId, "message.thread.read", "app_user",
                residentUserId.toString(), AuditOutcome.SUCCESS);
        return messages.findByCondominiumIdAndResidentUserId(condominiumId, residentUserId, pageable);
    }

    /**
     * Responde, como portaria, à conversa de um morador.
     *
     * @param condominiumId condomínio
     * @param residentUserId usuário do morador
     * @param text texto
     */
    @Transactional
    public DirectMessage replyAsDesk(UUID condominiumId, UUID residentUserId, String text) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "message.send.desk");
        requireResidentUser(condominiumId, residentUserId);
        DirectMessage m = messages.save(new DirectMessage(condominiumId, residentUserId, actor.userId(), false,
                text.trim(), Instant.now(clock)));
        audit.record(actor.userId(), null, condominiumId, "message.send.desk", "direct_message",
                m.getId().toString(), AuditOutcome.SUCCESS);
        notifications.notifyUser(condominiumId, residentUserId, NotificationService.DIRECT_MESSAGE,
                "Nova mensagem da portaria", "Abra a aba Mensagens para ler a resposta.");
        return m;
    }

    private void requireResidentUser(UUID condominiumId, UUID residentUserId) {
        if (!residents.findUserIdsOfCondominium(condominiumId).contains(residentUserId)) {
            throw new NotFoundException("Resident not found");
        }
    }
}
