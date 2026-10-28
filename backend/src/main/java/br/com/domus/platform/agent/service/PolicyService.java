package br.com.domus.platform.agent.service;

import br.com.domus.platform.agent.entity.Agent;
import br.com.domus.platform.access.service.CredentialService;
import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.repository.DeviceRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Monta e assina a política de contingência: o que o agente pode aceitar sem a central, até quando e em que
 * versão. O agente verifica a assinatura sobre os bytes exatos recebidos e recusa política vencida.
 */
@Service
public class PolicyService {

    static final int MAX_CREDENTIALS = 5000;

    /** Política serializada e sua assinatura HMAC-SHA256 (Base64url). */
    public record Envelope(String policy, String signature) {
    }

    private final AgentService agents;
    private final CredentialService credentials;
    private final DeviceRepository devices;
    private final Clock clock;
    private final Duration ttl;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Construtor de PolicyService; recebe as dependências por injeção. */
    public PolicyService(AgentService agents, CredentialService credentials, DeviceRepository devices, Clock clock,
                         @Value("${portaria.agent.policy-ttl:PT6H}") Duration ttl) {
        this.agents = agents;
        this.credentials = credentials;
        this.devices = devices;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Devolve o prazo de validade da política enviada ao agente.
     */
    public Duration ttl() {
        return ttl;
    }

    @Transactional(readOnly = true)
    /**
     * Monta e assina o envelope de política para o agente.
     *
     * @param agent agente autenticado
     */
    public Envelope build(Agent agent) {
        Instant now = Instant.now(clock);
        long version = agents.currentVersion(agent.getCondominiumId());
        TreeSet<String> entrances = new TreeSet<>();
        for (Device d : devices.findByCondominiumId(agent.getCondominiumId())) {
            if ("ACCESS_CONTROLLER".equals(d.getKind())) {
                entrances.add(d.getEntrance());
            }
        }
        List<Map<String, Object>> creds = credentials.offlineCredentials(agent.getCondominiumId(), MAX_CREDENTIALS)
                .stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.id().toString());
                    m.put("sig", c.sig());
                    m.put("validFrom", c.validFrom().toString());
                    m.put("validUntil", c.validUntil().toString());
                    m.put("maxUses", c.maxUses());
                    m.put("usesSoFar", c.usesSoFar());
                    return m;
                }).toList();
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", version);
        policy.put("agentId", agent.getId().toString());
        policy.put("condominiumId", agent.getCondominiumId().toString());
        policy.put("issuedAt", now.toString());
        policy.put("validUntil", now.plus(ttl).toString());
        policy.put("allowOfflineEntry", true);
        policy.put("entrances", entrances);
        policy.put("credentials", creds);
        String json = mapper.writeValueAsString(policy);
        return new Envelope(json, sign(agent, json));
    }

    private String sign(Agent agent, String json) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(agents.policySecretFor(agent.getId()).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(json.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
