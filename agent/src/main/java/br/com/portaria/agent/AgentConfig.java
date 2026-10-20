package br.com.portaria.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Configuração do agente, lida de variáveis de ambiente (segredos nunca vão em arquivo versionado).
 *
 * @param centralUrl     URL base da central (use HTTPS em produção)
 * @param agentId        identidade do agente (recebida no cadastro)
 * @param apiKey         chave do agente (recebida no cadastro; rotacionável)
 * @param policySecret   segredo de verificação da política (recebido no cadastro)
 * @param dataDir        diretório dos dados locais (SQLite)
 * @param localKey       chave exigida pela API local do leitor
 * @param localPort      porta da API local (somente 127.0.0.1)
 * @param syncInterval   intervalo do ciclo de sincronização
 * @param requestTimeout tempo máximo de cada chamada à central
 */
public record AgentConfig(String centralUrl, UUID agentId, String apiKey, String policySecret, Path dataDir,
                          String localKey, int localPort, Duration syncInterval, Duration requestTimeout) {

    static AgentConfig fromEnv() {
        return new AgentConfig(required("PORTARIA_CENTRAL_URL"), UUID.fromString(required("PORTARIA_AGENT_ID")),
                required("PORTARIA_AGENT_KEY"), required("PORTARIA_POLICY_SECRET"),
                Path.of(System.getenv().getOrDefault("PORTARIA_DATA_DIR", "/var/lib/portaria-agent")),
                required("PORTARIA_LOCAL_KEY"), Integer.parseInt(System.getenv().getOrDefault("PORTARIA_LOCAL_PORT", "8099")),
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("PORTARIA_SYNC_SECONDS", "10"))),
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("PORTARIA_TIMEOUT_SECONDS", "5"))));
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return v;
    }
}
