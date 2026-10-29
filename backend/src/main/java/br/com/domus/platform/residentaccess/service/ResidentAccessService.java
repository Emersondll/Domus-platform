package br.com.domus.platform.residentaccess.service;

import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.residentaccess.entity.AccessIdentity;
import br.com.domus.platform.residentaccess.entity.ResidentAccessEvent;
import br.com.domus.platform.residentaccess.repository.AccessIdentityRepository;
import br.com.domus.platform.residentaccess.repository.ResidentAccessEventRepository;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.ResidentPassageRecorder;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.crypto.FieldCipher;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Entradas e saídas do próprio morador. Só se grava a passagem de quem tem identidade de acesso cadastrada; a consulta
 * é exclusiva do titular (a portaria e o síndico não veem o histórico pessoal) e cada consulta fica na auditoria.
 * O sentido vem do ponto de acesso; sem sentido confiável o evento é {@code UNKNOWN} e nunca se deduz "está dentro".
 */
@Service
public class ResidentAccessService implements ResidentPassageRecorder {

    /** Meios que a API aceita ao cadastrar uma identidade (FACE_REF fica reservado para a identificação facial). */
    static final Set<String> REGISTRABLE_KINDS = Set.of("CARD", "QR", "TERMINAL_USER", "APP");
    private static final Set<String> METHODS = Set.of("CARD", "QR", "TERMINAL_USER", "APP", "FACE");
    private static final Set<String> DIRECTIONS = Set.of("IN", "OUT");
    /** Período máximo de uma consulta. */
    public static final Duration MAX_RANGE = Duration.ofDays(31);

    private final AccessIdentityRepository identities;
    private final ResidentAccessEventRepository events;
    private final ResidentRepository residents;
    private final FieldCipher cipher;
    private final AccessService access;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int retentionDays;

    /** Construtor de ResidentAccessService; recebe as dependências por injeção. */
    public ResidentAccessService(AccessIdentityRepository identities, ResidentAccessEventRepository events,
                                 ResidentRepository residents, FieldCipher cipher, AccessService access,
                                 AuditService audit, MeterRegistry meters, Clock clock,
                                 @org.springframework.beans.factory.annotation.Value("${portaria.retention.resident-access-days:90}") int retentionDays) {
        this.retentionDays = retentionDays;
        this.identities = identities;
        this.events = events;
        this.residents = residents;
        this.cipher = cipher;
        this.access = access;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(Passage p) {
        String ref = normalize(p.identityRef());
        if (ref == null) {
            return;
        }
        Optional<AccessIdentity> identity = identities.findByCondominiumIdAndRefHashAndActiveTrue(
                p.condominiumId(), cipher.blindIndex(ref));
        if (identity.isEmpty()) {
            // Pessoa fora do cadastro: não se acumula dado pessoal, só se conta (sem tags, sem valor da referência).
            meters.counter("portaria.resident_access.unknown_ref").increment();
            return;
        }
        String direction = in(DIRECTIONS, p.configuredDirection()) ? p.configuredDirection()
                : in(DIRECTIONS, p.reportedDirection()) ? p.reportedDirection() : "UNKNOWN";
        String kind = identity.get().getKind();
        String method = in(METHODS, p.reportedMethod()) ? p.reportedMethod()
                : "FACE_REF".equals(kind) ? "FACE" : kind;
        events.save(new ResidentAccessEvent(p.condominiumId(), identity.get().getResidentId(), p.deviceId(),
                p.entrance(), direction, method, p.granted() ? "GRANTED" : "DENIED", p.occurredAt(),
                Instant.now(clock)));
    }

    // ---- administração das identidades (síndico ou administração) ----

    /**
     * Cadastra um meio de acesso do morador (cartão, QR, aplicativo ou referência do equipamento).
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @param kind tipo
     * @param ref referência do meio de acesso
     */
    @Transactional
    public AccessIdentity register(UUID condominiumId, UUID residentId, String kind, String ref) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "access.identity.register");
        if (!REGISTRABLE_KINDS.contains(kind)) {
            throw new InvalidRequestException("Unsupported identity kind");
        }
        String normalized = normalize(ref);
        if (normalized == null) {
            throw new InvalidRequestException("Reference is required");
        }
        requireActiveResident(condominiumId, residentId);
        String hash = cipher.blindIndex(normalized);
        if (identities.findByCondominiumIdAndRefHashAndActiveTrue(condominiumId, hash).isPresent()) {
            throw new ConflictException("This reference is already in use");
        }
        AccessIdentity saved = identities.save(new AccessIdentity(condominiumId, residentId, kind,
                cipher.encrypt(normalized), hash, Instant.now(clock)));
        audit.record(actor.userId(), null, condominiumId, "access.identity.register", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return saved;
    }

    /**
     * Lista os meios de acesso do morador.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @return lista de resultados
     */
    @Transactional
    public List<AccessIdentity> listIdentities(UUID condominiumId, UUID residentId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "access.identity.list");
        requireResident(condominiumId, residentId);
        List<AccessIdentity> list = identities.findByCondominiumIdAndResidentIdOrderByCreatedAtDesc(condominiumId,
                residentId);
        audit.record(actor.userId(), null, condominiumId, "access.identity.list", "resident", residentId.toString(),
                AuditOutcome.SUCCESS);
        return list;
    }

    /**
     * Revoga um meio de acesso do morador.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @param identityId meio de acesso
     */
    @Transactional
    public AccessIdentity revoke(UUID condominiumId, UUID residentId, UUID identityId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "access.identity.revoke");
        AccessIdentity identity = identities.findByIdAndCondominiumIdAndResidentId(identityId, condominiumId,
                residentId).orElseThrow(() -> new NotFoundException("Identity not found"));
        if (identity.isActive()) {
            identity.revoke(Instant.now(clock));
        }
        audit.record(actor.userId(), null, condominiumId, "access.identity.revoke", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return identity;
    }

    // ---- consulta do titular ----

    /** O método não é {@code readOnly}: a auditoria de sucesso é gravada na mesma transação. */
    @Transactional
    public Page<ResidentAccessEvent> myAccesses(UUID condominiumId, Instant from, Instant to, int page, int size) {
        ResidentContext me = access.requireResident(condominiumId, "access.history.view.self");
        Instant end = to == null ? Instant.now(clock) : to;
        Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new InvalidRequestException("Invalid period");
        }
        Page<ResidentAccessEvent> result = events.findByCondominiumIdAndResidentIdAndOccurredAtBetweenOrderByOccurredAtDesc(
                condominiumId, me.residentId(), start, end, PageRequest.of(Math.max(page, 0), clamp(size)));
        audit.record(me.actor().userId(), null, condominiumId, "access.history.view.self", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
        return result;
    }

    /**
     * Consulta a última passagem registrada do morador autenticado.
     *
     * @param condominiumId condomínio
     * @return resultado, se existir
     */
    @Transactional
    public Optional<ResidentAccessEvent> myLast(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "access.history.view.self");
        Optional<ResidentAccessEvent> last = events.findFirstByCondominiumIdAndResidentIdOrderByOccurredAtDesc(
                condominiumId, me.residentId());
        audit.record(me.actor().userId(), null, condominiumId, "access.history.view.self", "resident",
                me.residentId().toString(), AuditOutcome.SUCCESS);
        return last;
    }

    /** Prazo em que os registros de acesso são apagados; só quem é morador do condomínio consulta (os demais recebem 404). */
    @Transactional(readOnly = true)
    public int retentionDays(UUID condominiumId) {
        access.requireResident(condominiumId, "access.history.policy.self");
        return retentionDays;
    }

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private void requireResident(UUID condominiumId, UUID residentId) {
        residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
    }

    private void requireActiveResident(UUID condominiumId, UUID residentId) {
        if (!residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found")).isActive()) {
            throw new ConflictException("Resident is inactive");
        }
    }

    /** {@code Set.of} recusa {@code null} em {@code contains}; os campos opcionais do evento podem faltar. */
    private static boolean in(Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }

    static String normalize(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return ref.trim().toUpperCase(Locale.ROOT);
    }

    /** Remove tudo que é pessoal deste morador (usado pela anonimização do titular). */
    @Transactional
    public void eraseOf(UUID condominiumId, UUID residentId) {
        events.deleteByCondominiumIdAndResidentId(condominiumId, residentId);
        identities.deleteByCondominiumIdAndResidentId(condominiumId, residentId);
    }

    /** Dados do morador para a exportação (acesso do titular). */
    @Transactional(readOnly = true)
    public List<ResidentAccessEvent> exportOf(UUID condominiumId, UUID residentId) {
        return events.findByCondominiumIdAndResidentIdOrderByOccurredAtDesc(condominiumId, residentId);
    }

}
