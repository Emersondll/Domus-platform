package br.com.domus.platform.agent.service;

import br.com.domus.platform.agent.entity.Agent;
import br.com.domus.platform.agent.entity.PolicyVersionRow;
import br.com.domus.platform.agent.repository.AgentRepository;
import br.com.domus.platform.agent.repository.PolicyVersionRepository;
import br.com.domus.platform.shared.ConnectivityLookup;
import br.com.domus.platform.shared.PolicyVersion;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.ForbiddenException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.CondominiumLookup;
import br.com.domus.platform.shared.tenant.Actor;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identidade dos agentes locais: cadastro, autenticação por chave (rotacionável, com janela de transição),
 * revogação e leitura de conectividade. A chave nunca é guardada em claro; o segredo de assinatura da política é
 * derivado do segredo mestre e do ID do agente, então não precisa ser armazenado.
 */
@Service
public class AgentService implements ConnectivityLookup, PolicyVersion {

    public static final Duration ONLINE_WINDOW = Duration.ofSeconds(90);
    static final Duration KEY_GRACE = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Pacote de cadastro, exibido uma única vez ao instalar o agente. */
    public record Enrollment(Agent agent, String apiKey, String policySecret) {
    }

    private final AgentRepository agents;
    private final PolicyVersionRepository versions;
    private final AccessService access;
    private final CondominiumLookup condominiums;
    private final AuditService audit;
    private final Clock clock;
    private final byte[] masterSecret;

    /** Construtor de AgentService; recebe as dependências por injeção. */
    public AgentService(AgentRepository agents, PolicyVersionRepository versions, AccessService access,
                        CondominiumLookup condominiums, AuditService audit, Clock clock,
                        @Value("${portaria.agent.policy-master-secret:}") String masterSecret) {
        if (masterSecret == null || masterSecret.length() < 32) {
            throw new IllegalStateException("portaria.agent.policy-master-secret (AGENT_POLICY_MASTER_SECRET) "
                    + "must have at least 32 characters");
        }
        this.agents = agents;
        this.versions = versions;
        this.access = access;
        this.condominiums = condominiums;
        this.audit = audit;
        this.clock = clock;
        this.masterSecret = masterSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Cadastra um agente local e devolve as credenciais, exibidas uma única vez.
     *
     * @param condominiumId condomínio
     * @param name nome
     */
    @Transactional
    public Enrollment enroll(UUID condominiumId, String name) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "agent.enroll");
        if (agents.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ConflictException("Agent name already in use");
        }
        String apiKey = newKey();
        Agent agent = agents.save(new Agent(condominiumId, name, sha256(apiKey)));
        audit.record(actor.userId(), null, condominiumId, "agent.enroll", "agent", agent.getId().toString(),
                AuditOutcome.SUCCESS);
        return new Enrollment(agent, apiKey, policySecretFor(agent.getId()));
    }

    /**
     * Lista os agentes do condomínio.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    @Transactional(readOnly = true)
    public List<Agent> list(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.READ, "agent.list");
        return agents.findByCondominiumId(condominiumId);
    }

    /**
     * Revoga o agente do condomínio.
     *
     * @param condominiumId condomínio
     * @param agentId agente
     */
    @Transactional
    public void revoke(UUID condominiumId, UUID agentId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "agent.revoke");
        Agent agent = agents.findByIdAndCondominiumId(agentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Agent not found"));
        agent.revoke();
        audit.record(actor.userId(), null, condominiumId, "agent.revoke", "agent", agentId.toString(),
                AuditOutcome.SUCCESS);
    }

    /**
     * Autentica o agente pelo ID e pela chave. Falhas são indistinguíveis (não revela se o agente existe).
     * Usar a chave nova encerra a janela de transição da anterior.
     */
    @Transactional
    public Agent authenticate(UUID agentId, String apiKey, Long policyVersion, Integer backlog) {
        Instant now = Instant.now(clock);
        Agent agent = agents.findById(agentId).filter(a -> "ACTIVE".equals(a.getStatus())).orElse(null);
        if (agent == null || apiKey == null || condominiums.isSuspended(agent.getCondominiumId())) {
            throw new ForbiddenException("Invalid agent credentials");
        }
        String given = sha256(apiKey);
        boolean current = constantTimeEquals(given, agent.getKeyHash());
        boolean previous = agent.getPrevKeyHash() != null && agent.getPrevKeyExpiresAt() != null
                && agent.getPrevKeyExpiresAt().isAfter(now) && constantTimeEquals(given, agent.getPrevKeyHash());
        if (!current && !previous) {
            throw new ForbiddenException("Invalid agent credentials");
        }
        if (current && agent.getPrevKeyHash() != null) {
            agent.dropPreviousKey();
        }
        agent.touch(now, policyVersion, backlog);
        return agent;
    }

    /**
     * Gera uma nova chave de API para o agente e devolve o valor em claro uma única vez.
     *
     * @param authenticated agente já autenticado
     * @return texto resultante
     */
    @Transactional
    public String rotateKey(Agent authenticated) {
        Agent agent = agents.findById(authenticated.getId()).orElseThrow();
        String newKey = newKey();
        agent.rotate(sha256(newKey), Instant.now(clock).plus(KEY_GRACE));
        audit.record(null, null, agent.getCondominiumId(), "agent.rotate-key", "agent", agent.getId().toString(),
                AuditOutcome.SUCCESS);
        return newKey;
    }

    /**
     * Indica se o agente teve contato dentro da janela de conexão.
     *
     * @param agent agente autenticado
     * @return verdadeiro ou falso
     */
    public boolean isOnline(Agent agent) {
        return agent.getLastSeenAt() != null
                && agent.getLastSeenAt().isAfter(Instant.now(clock).minus(ONLINE_WINDOW));
    }

    @Override
    @Transactional(readOnly = true)
    public String connectivitySummary(UUID condominiumId) {
        List<Agent> active = agents.findByCondominiumId(condominiumId).stream()
                .filter(a -> "ACTIVE".equals(a.getStatus())).toList();
        if (active.isEmpty()) {
            return "NO_AGENT";
        }
        long offline = active.stream().filter(a -> !isOnline(a)).count();
        int backlog = active.stream().mapToInt(Agent::getBacklog).sum();
        String state = offline == 0 ? "ONLINE" : "OFFLINE " + offline + "/" + active.size();
        return backlog > 0 ? state + " (" + backlog + " eventos pendentes)" : state;
    }

    @Override
    public void bump(UUID condominiumId) {
        versions.bump(condominiumId);
    }

    /**
     * Devolve a versão atual da política do condomínio.
     *
     * @param condominiumId condomínio
     * @return valor calculado
     */
    @Transactional(readOnly = true)
    public long currentVersion(UUID condominiumId) {
        return versions.findByCondominiumId(condominiumId).map(PolicyVersionRow::getVersion).orElse(1L);
    }

    /** Segredo com que o agente verifica a assinatura da política; derivado, não armazenado. */
    public String policySecretFor(UUID agentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(("policy:" + agentId).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
