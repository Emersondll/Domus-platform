package br.com.portaria.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Limitação de taxa e bloqueio por força bruta, com limites baixos (contexto próprio). */
@SpringBootTest(properties = {
        "portaria.rate-limit.agent-per-minute=5", "portaria.rate-limit.api-per-minute=8",
        "portaria.rate-limit.device-per-minute=120", "portaria.rate-limit.validate-per-minute=100000",
        "portaria.rate-limit.max-auth-failures=3"})
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private static RequestPostProcessor ip(String addr) {
        return r -> {
            r.setRemoteAddr(addr);
            return r;
        };
    }

    @Test
    void genericApiIsLimitedPerClientAddressAndRespondsWithRetryAfter() throws Exception {
        int ok = 0;
        int limited = 0;
        String retryAfter = null;
        for (int i = 0; i < 12; i++) {
            var r = mvc.perform(get("/api/v1/condominiums").with(ip("10.0.0.1"))).andReturn().getResponse();
            if (r.getStatus() == 429) {
                limited++;
                retryAfter = r.getHeader("Retry-After");
            } else {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(8);
        assertThat(limited).isEqualTo(4);
        assertThat(retryAfter).isNotNull();
        // outro endereço não é afetado
        assertThat(mvc.perform(get("/api/v1/condominiums").with(ip("10.0.0.2"))).andReturn().getResponse().getStatus())
                .isNotEqualTo(429);
    }

    @Test
    void repeatedAgentCredentialFailuresLockOutTheAddress() throws Exception {
        String agent = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            int status = mvc.perform(get("/api/v1/agent/commands").with(ip("10.0.0.9")).header("X-Agent-Id", agent)
                    .header("X-Agent-Key", "wrong-" + i)).andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(403);
        }
        // a partir daqui o endereço está bloqueado, mesmo com credencial correta ou errada
        var r = mvc.perform(get("/api/v1/agent/commands").with(ip("10.0.0.9")).header("X-Agent-Id", agent)
                .header("X-Agent-Key", "anything")).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(429);
        assertThat(r.getHeader("Retry-After")).isNotNull();
        assertThat(mvc.perform(get("/api/v1/agent/commands").with(ip("10.0.0.10")).header("X-Agent-Id", agent)
                .header("X-Agent-Key", "wrong")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void deviceEventIngestionIsLimitedPerDevice() throws Exception {
        String device = UUID.randomUUID().toString();
        int limited = 0;
        for (int i = 0; i < 130; i++) {
            int status = mvc.perform(post("/api/v1/devices/" + device + "/events").with(ip("10.0.1." + (i % 200)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"HEARTBEAT\",\"occurredAt\":\"2030-01-01T00:00:00Z\"}"))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                limited++;
            }
        }
        assertThat(limited).isEqualTo(10); // limite padrão de 120/min por dispositivo
    }
}
