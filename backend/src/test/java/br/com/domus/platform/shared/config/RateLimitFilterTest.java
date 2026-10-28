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
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3);
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
        RateLimitFilter filter = new RateLimitFilter(clock, true, false, 120, 120, 120, 600, 3);
        for (int i = 0; i < 3; i++) {
            call(filter, "10.9.9.9", "/api/v1/agent/policy", 401);
        }
        assertThat(call(filter, "10.9.9.9", "/api/v1/agent/policy", 200).getStatus()).isEqualTo(429);

        now.addAndGet(6 * 60_000);
        call(filter, "10.1.1.1", "/api/v1/condominiums", 200); // outra origem dispara a varredura
        assertThat(filter.trackedKeys()).isLessThanOrEqualTo(2);
    }
}
