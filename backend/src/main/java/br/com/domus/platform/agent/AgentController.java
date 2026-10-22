package br.com.domus.platform.agent;

import br.com.domus.platform.shared.error.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API consumida pelo agente local. Sem JWT: cada chamada é autenticada por {@code X-Agent-Id} e
 * {@code X-Agent-Key} (chave rotacionável). O transporte deve ser TLS (proxy reverso em produção).
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentService agents;
    private final PolicyService policies;
    private final SyncService sync;
    private final AgentCommandRepository commandQueue;
    private final Clock clock;

    public AgentController(AgentService agents, PolicyService policies, SyncService sync,
                           AgentCommandRepository commandQueue, Clock clock) {
        this.agents = agents;
        this.policies = policies;
        this.sync = sync;
        this.commandQueue = commandQueue;
        this.clock = clock;
    }

    /**
     * @param eventId      identificador único gerado no agente (torna o reenvio idempotente)
     * @param type         OFFLINE_ENTRY_GRANTED, OFFLINE_ENTRY_DENIED, OFFLINE_ENTRY_UNCERTAIN, DOOR_OPENED,
     *                     DOOR_CLOSED, TAMPER ou COMMAND_FAILED
     * @param occurredAt   instante do evento segundo o agente
     * @param credentialId credencial envolvida, quando houver
     * @param deviceId     dispositivo envolvido, quando houver
     * @param commandId    comando ao qual o evento responde, quando houver
     * @param entrance     entrada
     * @param reason       motivo
     */
    public record EventDto(@NotNull UUID eventId, @NotBlank String type, @NotNull Instant occurredAt,
                           UUID credentialId, UUID deviceId, UUID commandId, @Size(max = 60) String entrance,
                           @Size(max = 40) String reason) {
    }

    /** @param events lote de eventos, do mais antigo ao mais novo (máximo 200) */
    public record EventBatch(@NotNull @Size(max = 200) List<@Valid EventDto> events) {
    }

    /**
     * @param eventId identificador do evento
     * @param status  APPLIED, DUPLICATE, CONFLICT ou REJECTED (qualquer um dos três primeiros autoriza o descarte local)
     * @param detail  detalhe do conflito ou rejeição
     */
    public record EventResult(UUID eventId, String status, String detail) {
    }

    /**
     * @param policy    política serializada; a assinatura vale sobre exatamente estes bytes
     * @param signature HMAC-SHA256 em Base64url
     */
    public record PolicyResponse(String policy, String signature) {
    }

    /**
     * @param commandId      comando de abertura a executar (uma única vez)
     * @param deviceId       dispositivo alvo
     * @param entrance       entrada
     * @param expiresAt      depois deste instante o comando NÃO deve ser executado
     */
    public record CommandDto(UUID commandId, UUID deviceId, String entrance, Instant expiresAt) {
    }

    @GetMapping("/policy")
    public ResponseEntity<PolicyResponse> policy(@RequestHeader("X-Agent-Id") UUID id,
            @RequestHeader("X-Agent-Key") String key,
            @RequestHeader(value = "X-Agent-Backlog", required = false) Integer backlog,
            @RequestParam(required = false) Long sinceVersion, @RequestParam(required = false) Long ageSeconds) {
        Agent agent = agents.authenticate(id, key, sinceVersion, backlog);
        long current = agents.currentVersion(agent.getCondominiumId());
        boolean fresh = sinceVersion != null && sinceVersion == current && ageSeconds != null
                && Duration.ofSeconds(ageSeconds).compareTo(policies.ttl().dividedBy(2)) < 0;
        if (fresh) {
            return ResponseEntity.noContent().build();
        }
        PolicyService.Envelope env = policies.build(agent);
        return ResponseEntity.ok(new PolicyResponse(env.policy(), env.signature()));
    }

    @PostMapping("/events")
    public Map<String, List<EventResult>> events(@RequestHeader("X-Agent-Id") UUID id,
            @RequestHeader("X-Agent-Key") String key,
            @RequestHeader(value = "X-Agent-Backlog", required = false) Integer backlog,
            @Valid @RequestBody EventBatch batch) {
        Agent agent = agents.authenticate(id, key, null, backlog);
        List<SyncService.Incoming> incoming = batch.events().stream().map(e -> new SyncService.Incoming(e.eventId(),
                e.type(), e.occurredAt(), e.credentialId(), e.deviceId(), e.commandId(), e.entrance(),
                e.reason())).toList();
        return Map.of("results", sync.process(agent, incoming).stream()
                .map(r -> new EventResult(r.eventId(), r.status(), r.detail())).toList());
    }

    @GetMapping("/commands")
    @Transactional
    public Map<String, List<CommandDto>> commands(@RequestHeader("X-Agent-Id") UUID id,
            @RequestHeader("X-Agent-Key") String key,
            @RequestHeader(value = "X-Agent-Backlog", required = false) Integer backlog) {
        Agent agent = agents.authenticate(id, key, null, backlog);
        Instant now = Instant.now(clock);
        List<AgentCommand> pending = commandQueue.findPending(agent.getId(), now);
        pending.forEach(c -> c.deliver(now));
        return Map.of("commands", pending.stream()
                .map(c -> new CommandDto(c.getId(), c.getDeviceId(), c.getEntrance(), c.getExpiresAt())).toList());
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@RequestHeader("X-Agent-Id") UUID id, @RequestHeader("X-Agent-Key") String key,
            @RequestHeader(value = "X-Agent-Backlog", required = false) Integer backlog,
            @RequestParam(required = false) Long policyVersion) {
        agents.authenticate(id, key, policyVersion, backlog);
    }

    @PostMapping("/rotate-key")
    public Map<String, String> rotate(@RequestHeader("X-Agent-Id") UUID id, @RequestHeader("X-Agent-Key") String key) {
        Agent agent = agents.authenticate(id, key, null, null);
        if (!"ACTIVE".equals(agent.getStatus())) {
            throw new ForbiddenException("Invalid agent credentials");
        }
        return Map.of("apiKey", agents.rotateKey(agent));
    }
}
