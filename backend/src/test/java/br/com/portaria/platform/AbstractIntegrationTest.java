package br.com.portaria.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Base dos testes de integração: um PostgreSQL real compartilhado e contexto Spring em cache. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractIntegrationTest {

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
        registry.add("portaria.devices.simulator-enabled", () -> "true");
        registry.add("portaria.devices.http-allowed-hosts", () -> "localhost,127.0.0.1");
        registry.add("portaria.devices.confirmation-timeout", () -> "PT2S");
        // O vencimento é disparado manualmente nos testes, para não depender do relógio do agendador.
        registry.add("portaria.devices.expiry-interval-ms", () -> "3600000");
    }

    @MockitoBean
    JwtDecoder jwtDecoder;
}
