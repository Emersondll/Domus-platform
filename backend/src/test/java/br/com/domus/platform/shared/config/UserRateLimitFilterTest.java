package br.com.domus.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** O limite por pessoa conta cada usuário separadamente, mesmo com todos saindo pelo mesmo IP. */
class UserRateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(5_000_000);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    };

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int call(UserRateLimitFilter filter, String user) throws Exception {
        if (user == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, "n", List.of()));
            SecurityContextHolder.getContext().getAuthentication().setAuthenticated(true);
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/condominiums");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((MockHttpServletResponse) res).setStatus(200));
        return response.getStatus();
    }

    @Test
    void eachUserHasHisOwnBudgetAndTheBudgetRenewsEachMinute() throws Exception {
        UserRateLimitFilter filter = new UserRateLimitFilter(clock, true, 5);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "operador-a")).isEqualTo(200);
        }
        assertThat(call(filter, "operador-a")).as("o sexto do mesmo usuário").isEqualTo(429);
        assertThat(call(filter, "operador-b")).as("outro usuário no mesmo IP não é afetado").isEqualTo(200);
        now.addAndGet(61_000);
        assertThat(call(filter, "operador-a")).as("a janela seguinte").isEqualTo(200);
    }

    @Test
    void requestsWithoutAnAuthenticatedUserAreLeftToTheOtherFilters() throws Exception {
        UserRateLimitFilter filter = new UserRateLimitFilter(clock, true, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, null)).isEqualTo(200);
        }
        assertThat(filter.trackedKeys()).isZero();
    }

    @Test
    void expiredUsersAreForgotten() throws Exception {
        UserRateLimitFilter filter = new UserRateLimitFilter(clock, true, 5);
        for (int i = 0; i < 50; i++) {
            call(filter, "u" + i);
        }
        assertThat(filter.trackedKeys()).isEqualTo(50);
        now.addAndGet(2 * 60_000);
        call(filter, "novo");
        assertThat(filter.trackedKeys()).isEqualTo(1);
    }
}
