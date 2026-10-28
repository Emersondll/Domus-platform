package br.com.domus.platform.agent.controller;

import br.com.domus.platform.agent.entity.SyncConflict;
import br.com.domus.platform.agent.service.AgentService;
import br.com.domus.platform.agent.service.SyncConflictService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administração de agentes e dos conflitos de sincronização (JWT, escopada por condomínio). */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class AgentAdminController {

    private final AgentService agents;
    private final SyncConflictService conflicts;

    /** Construtor de AgentAdminController; recebe as dependências por injeção. */
    public AgentAdminController(AgentService agents, SyncConflictService conflicts) {
        this.agents = agents;
        this.conflicts = conflicts;
    }

    /** @param name nome do agente (ex.: servidor da portaria) */
    public record EnrollRequest(@NotBlank @Size(max = 80) String name) {
    }

    /**
     * @param id           identificador do agente
     * @param name         nome
     * @param apiKey       chave de API; exibida somente no cadastro
     * @param policySecret segredo de verificação da política; exibido somente no cadastro
     */
    public record EnrollResponse(UUID id, String name, String apiKey, String policySecret) {
    }

    /**
     * @param id                 identificador
     * @param name               nome
     * @param status             ACTIVE ou REVOKED
     * @param online             visto nos últimos 90 segundos
     * @param lastSeenAt         último contato
     * @param lastPolicyVersion  versão de política confirmada pelo agente
     * @param backlog            eventos ainda não sincronizados no agente
     */
    public record AgentResponse(UUID id, String name, String status, boolean online, Instant lastSeenAt,
                                Long lastPolicyVersion, int backlog) {
    }

    /**
     * @param id         identificador
     * @param eventId    evento do agente que originou o conflito
     * @param kind       REVOKED_BEFORE_USE, QUOTA_EXCEEDED, UNKNOWN_CREDENTIAL, EXPIRED_AT_USE ou UNCERTAIN_ENTRY
     * @param detail     detalhe
     * @param createdAt  criação
     * @param resolvedAt resolução, nulo se aberto
     */
    public record ConflictResponse(UUID id, UUID eventId, String kind, String detail, Instant createdAt,
                                   Instant resolvedAt) {
        static ConflictResponse from(SyncConflict c) {
            return new ConflictResponse(c.getId(), c.getEventId(), c.getKind(), c.getDetail(), c.getCreatedAt(),
                    c.getResolvedAt());
        }
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/agents`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollResponse enroll(@PathVariable UUID condominiumId, @Valid @RequestBody EnrollRequest r) {
        AgentService.Enrollment e = agents.enroll(condominiumId, r.name());
        return new EnrollResponse(e.agent().getId(), e.agent().getName(), e.apiKey(), e.policySecret());
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/agents`.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    @GetMapping("/agents")
    public List<AgentResponse> list(@PathVariable UUID condominiumId) {
        return agents.list(condominiumId).stream().map(a -> new AgentResponse(a.getId(), a.getName(), a.getStatus(),
                agents.isOnline(a), a.getLastSeenAt(), a.getLastPolicyVersion(), a.getBacklog())).toList();
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/agents/{agentId}/revoke`.
     *
     * @param condominiumId condomínio
     * @param agentId agente
     */
    @PostMapping("/agents/{agentId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID condominiumId, @PathVariable UUID agentId) {
        agents.revoke(condominiumId, agentId);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/sync-conflicts`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/sync-conflicts")
    public PageResponse<ConflictResponse> conflicts(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(conflicts.list(condominiumId, pageable), ConflictResponse::from);
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/sync-conflicts/{conflictId}/resolve`.
     *
     * @param condominiumId condomínio
     * @param conflictId conflito
     */
    @PostMapping("/sync-conflicts/{conflictId}/resolve")
    public ConflictResponse resolve(@PathVariable UUID condominiumId, @PathVariable UUID conflictId) {
        return ConflictResponse.from(conflicts.resolve(condominiumId, conflictId));
    }
}
