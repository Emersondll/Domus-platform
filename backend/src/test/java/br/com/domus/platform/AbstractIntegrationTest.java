package br.com.domus.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Base dos testes de integração: um PostgreSQL real compartilhado e contexto Spring em cache. */
// Limites altos: os testes de concorrência disparam dezenas de chamadas do mesmo IP. RateLimitIT usa limites baixos.
@SpringBootTest(properties = {
        "portaria.rate-limit.validate-per-minute=100000", "portaria.rate-limit.api-per-minute=100000",
        "portaria.rate-limit.agent-per-minute=100000", "portaria.rate-limit.device-per-minute=100000",
        "portaria.rate-limit.max-auth-failures=100000"})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("portaria.credentials.hmac-secret", () -> "test-only-secret-0123456789-abcdefghijklmnop");
        registry.add("portaria.agent.policy-master-secret", () -> "test-only-agent-master-0123456789-abcdefghij");
        registry.add("portaria.crypto.keys", () -> "t1:ZGV2LW9ubHktZmllbGQta2V5LTAxMjM0NTY3ODktYWI=");
        registry.add("portaria.crypto.blind-index-key", () -> "test-only-blind-index-key-0123456789-abcdef");
        registry.add("portaria.devices.simulator-enabled", () -> "true");
        registry.add("portaria.devices.http-allowed-hosts", () -> "localhost,127.0.0.1");
        registry.add("portaria.devices.confirmation-timeout", () -> "PT2S");
        registry.add("portaria.snapshots.enabled", () -> "true");
        registry.add("portaria.retention.direct-message-days", () -> "30");
        registry.add("portaria.resident-photo.enabled", () -> "true");
        registry.add("portaria.media.enabled", () -> "true");
        registry.add("portaria.media.token-secret", () -> "test-only-media-token-0123456789-abcdefghij");
        registry.add("portaria.media.publish-secret", () -> "test-only-publish-secret");
        registry.add("portaria.media.public-hls-url", () -> "http://media.test:8888");
        // O vencimento é disparado manualmente nos testes, para não depender do relógio do agendador.
        registry.add("portaria.devices.expiry-interval-ms", () -> "3600000");
    }

    @MockitoBean
    protected JwtDecoder jwtDecoder;
}
