package br.com.domus.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Garante que as tabelas do limitador de taxa não crescem sem limite quando as origens são todas diferentes. */
class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    };

    private MockHttpServletResponse call(RateLimitFilter filter, String ip, String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(status));
        return response;
    }

    @Test
    void expiredEntriesOfManyDifferentOriginsAreSweptAway() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3, 6000);
        for (int i = 0; i < 200; i++) {
            call(filter, "10.0.0." + i, "/api/v1/agent/policy", 401); // falha de autenticação de máquina
        }
        assertThat(filter.trackedKeys()).isGreaterThanOrEqualTo(400); // uma janela e um contador de falhas por origem

        now.addAndGet(6 * 60_000); // passam a janela e o bloqueio
        call(filter, "192.168.0.1", "/api/v1/condominiums", 200);

        assertThat(filter.trackedKeys()).isLessThanOrEqualTo(2);
    }

    @Test
    void anOriginBlockedAfterRepeatedFailuresIsReleasedAndForgotten() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3, 6000);
        for (int i = 0; i < 3; i++) {
            call(filter, "10.9.9.9", "/api/v1/agent/policy", 401);
        }
        assertThat(call(filter, "10.9.9.9", "/api/v1/agent/policy", 200).getStatus()).isEqualTo(429);

        now.addAndGet(6 * 60_000);
        call(filter, "10.1.1.1", "/api/v1/condominiums", 200); // outra origem dispara a varredura
        assertThat(filter.trackedKeys()).isLessThanOrEqualTo(2);
    }

    private MockHttpServletResponse callWith(RateLimitFilter filter, String ip, String path, int status,
                                             java.util.Map<String, String> headers) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        headers.forEach(request::addHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(status));
        return response;
    }

    private static final String BAD_AGENT = "11111111-1111-1111-1111-111111111111";
    private static final String GOOD_AGENT = "22222222-2222-2222-2222-222222222222";

    @Test
    void aMisconfiguredAgentDoesNotLockOutAnotherAgentBehindTheSameIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3, 6000);
        for (int i = 0; i < 3; i++) {
            callWith(filter, "203.0.113.5", "/api/v1/agent/policy", 401, java.util.Map.of("X-Agent-Id", BAD_AGENT));
        }
        assertThat(callWith(filter, "203.0.113.5", "/api/v1/agent/policy", 200, java.util.Map.of("X-Agent-Id", BAD_AGENT)).getStatus())
                .as("quem errou a chave continua bloqueado").isEqualTo(429);
        assertThat(callWith(filter, "203.0.113.5", "/api/v1/agent/policy", 200, java.util.Map.of("X-Agent-Id", GOOD_AGENT)).getStatus())
                .as("o outro agente do mesmo IP segue funcionando").isEqualTo(200);
    }

    @Test
    void sprayingManyAgentIdsFromOneIpIsStillBlockedByTheIpCeiling() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3, 6000);
        int blockedAt = -1;
        for (int i = 0; i < 100 && blockedAt < 0; i++) {
            String id = String.format("00000000-0000-0000-0000-%012d", i);
            int status = callWith(filter, "198.51.100.7", "/api/v1/agent/policy", 401, java.util.Map.of("X-Agent-Id", id)).getStatus();
            if (status == 429) {
                blockedAt = i;
            }
        }
        assertThat(blockedAt).as("com 3 falhas por dupla, o teto por IP é 30").isEqualTo(30);
    }

    @Test
    void changingTheAgentIdHeaderDoesNotEscapeThePerAgentLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 5, 120, 120, 600, 1000, 6000);
        int limited = 0;
        for (int i = 0; i < 80; i++) {
            String id = String.format("00000000-0000-0000-0000-%012d", i);
            if (callWith(filter, "192.0.2.9", "/api/v1/agent/policy", 200, java.util.Map.of("X-Agent-Id", id)).getStatus() == 429) {
                limited++;
            }
        }
        assertThat(limited).as("5 por agente x 10 = 50 por IP; o resto é barrado mesmo com ids diferentes").isEqualTo(30);
        assertThat(callWith(filter, "192.0.2.10", "/api/v1/agent/policy", 200, java.util.Map.of("X-Agent-Id", "not-a-uuid")).getStatus())
                .as("identificador inválido cai no limite do IP, sem criar chave nova").isEqualTo(200);
    }

    @Test
    void manyPeopleBehindOneIpDoNotHitTheUnauthenticatedLimit() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3, 6000);
        int limited = 0;
        for (int i = 0; i < 2000; i++) {
            if (callWith(filter, "10.20.30.40", "/api/v1/condominiums", 200, java.util.Map.of("Authorization", "Bearer x.y.z")).getStatus() == 429) {
                limited++;
            }
        }
        assertThat(limited).as("com token, o teto por IP é 6000").isZero();
        int anonymousLimited = 0;
        for (int i = 0; i < 700; i++) {
            if (callWith(filter, "10.20.30.41", "/api/v1/condominiums", 401, java.util.Map.of()).getStatus() == 429) {
                anonymousLimited++;
            }
        }
        assertThat(anonymousLimited).as("sem token continua 600 por minuto").isEqualTo(100);
    }
}
