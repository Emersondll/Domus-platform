package br.com.domus.platform.agent.service;

import br.com.domus.platform.agent.entity.Agent;
import br.com.domus.platform.agent.entity.AgentEvent;
import br.com.domus.platform.agent.entity.SyncConflict;
import br.com.domus.platform.agent.repository.AgentEventRepository;
import br.com.domus.platform.agent.repository.SyncConflictRepository;
import br.com.domus.platform.access.service.CredentialService;
import br.com.domus.platform.devices.service.DeviceEventService;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recebe os eventos acumulados pelo agente. Entrega "pelo menos uma vez" + idempotência: o ID do evento é a
 * chave primária, então o reenvio após um ACK perdido é reconhecido como duplicata sem reaplicar efeitos.
 */
@Service
public class SyncService {

    static final Set<String> TYPES = Set.of("OFFLINE_ENTRY_GRANTED", "OFFLINE_ENTRY_DENIED", "OFFLINE_ENTRY_UNCERTAIN",
            "DOOR_OPENED", "DOOR_CLOSED", "TAMPER", "COMMAND_FAILED");

    /** Evento enviado pelo agente. */
    public record Incoming(UUID eventId, String type, Instant occurredAt, UUID credentialId, UUID deviceId,
                           UUID commandId, String entrance, String reason) {
    }

    /** Resultado por evento: APPLIED, DUPLICATE, CONFLICT ou REJECTED. */
    public record Result(UUID eventId, String status, String detail) {
    }

    private final AgentEventRepository events;
    private final SyncConflictRepository conflicts;
    private final CredentialService credentials;
    private final DeviceEventService deviceEvents;
    private final AuditService audit;
    private final Clock clock;
    private final MeterRegistry meters;

    /** Construtor de SyncService; recebe as dependências por injeção. */
    public SyncService(AgentEventRepository events, SyncConflictRepository conflicts, CredentialService credentials,
                       DeviceEventService deviceEvents, AuditService audit, Clock clock,
                       MeterRegistry meters) {
        this.events = events;
        this.conflicts = conflicts;
        this.credentials = credentials;
        this.deviceEvents = deviceEvents;
        this.audit = audit;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    /**
     * Processa, de forma idempotente, os eventos que o agente acumulou offline.
     *
     * @param agent agente autenticado
     * @param incoming eventos recebidos
     * @return lista de resultados
     */
    public List<Result> process(Agent agent, List<Incoming> incoming) {
        List<Result> results = new ArrayList<>();
        for (Incoming e : incoming) {
            Result result = processOne(agent, e);
            meters.counter("portaria.agent.events", "status", result.status().toLowerCase(java.util.Locale.ROOT)).increment();
            results.add(result);
        }
        return results;
    }

    private Result processOne(Agent agent, Incoming e) {
        if (!TYPES.contains(e.type())) {
            return new Result(e.eventId(), "REJECTED", "Unknown event type");
        }
        boolean needsCredential = Set.of("OFFLINE_ENTRY_GRANTED", "OFFLINE_ENTRY_UNCERTAIN").contains(e.type());
        if ((needsCredential && e.credentialId() == null)
                || ("COMMAND_FAILED".equals(e.type()) && (e.commandId() == null || e.deviceId() == null))) {
            return new Result(e.eventId(), "REJECTED", "Missing required fields for event type");
        }
        if (events.existsById(e.eventId())) {
            return new Result(e.eventId(), "DUPLICATE", null);
        }
        Instant now = Instant.now(clock);
        UUID condo = agent.getCondominiumId();
        events.saveAndFlush(new AgentEvent(e.eventId(), agent.getId(), condo, e.type(), e.credentialId(),
                e.deviceId(), e.commandId(), e.entrance(), e.reason(), e.occurredAt(), now));
        String entrance = e.entrance() == null ? "unknown" : e.entrance();
        switch (e.type()) {
            case "OFFLINE_ENTRY_GRANTED" -> {
                CredentialService.OfflineOutcome out = credentials.applyOfflineEntry(condo, e.credentialId(),
                        entrance, e.occurredAt());
                if (out.conflictKind() != null) {
                    return conflict(agent, e, out.conflictKind(), out.detail(), now);
                }
            }
            case "OFFLINE_ENTRY_DENIED" ->
                    credentials.recordOfflineDenial(condo, e.credentialId(), entrance, e.reason(), e.occurredAt());
            case "OFFLINE_ENTRY_UNCERTAIN" -> {
                // O agente não sabe se a porta abriu (queda no meio da operação): exige revisão humana.
                credentials.applyOfflineEntry(condo, e.credentialId(), entrance, e.occurredAt());
                return conflict(agent, e, "UNCERTAIN_ENTRY", "Agent could not confirm whether the door opened", now);
            }
            case "COMMAND_FAILED" -> deviceEvents.onCommandFailed(e.commandId(), e.deviceId(), e.reason());
            default -> {
                if (e.deviceId() != null) {
                    deviceEvents.onTrustedEvent(e.deviceId(), condo, e.type(), e.commandId());
                }
            }
        }
        return new Result(e.eventId(), "APPLIED", null);
    }

    private Result conflict(Agent agent, Incoming e, String kind, String detail, Instant now) {
        conflicts.save(new SyncConflict(e.eventId(), agent.getCondominiumId(), kind, detail, now));
        audit.record(null, null, agent.getCondominiumId(), "sync.conflict." + kind, "agent_event",
                e.eventId().toString(), AuditOutcome.FAILURE);
        return new Result(e.eventId(), "CONFLICT", kind);
    }
}
