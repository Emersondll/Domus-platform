package br.com.domus.platform.access;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.domus.platform.access.entity.VisitRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisitRequestTest {

    private final Instant created = Instant.parse("2030-01-01T10:00:00Z");

    private VisitRequest pending() {
        return new VisitRequest(UUID.randomUUID(), UUID.randomUUID(), null, "Tia Maria", "VISITOR", UUID.randomUUID(),
                created, created.plusSeconds(300));
    }

    @Test
    void pendingBecomesExpiredAfterTheDeadlineWithoutBeingStored() {
        VisitRequest r = pending();
        assertThat(r.effectiveStatus(created.plusSeconds(299))).isEqualTo("PENDING");
        assertThat(r.effectiveStatus(created.plusSeconds(300))).isEqualTo("EXPIRED");
        assertThat(r.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void decisionIsKeptEvenAfterTheDeadline() {
        VisitRequest r = pending();
        r.decide(true, UUID.randomUUID(), created.plusSeconds(10));
        assertThat(r.effectiveStatus(created.plusSeconds(9999))).isEqualTo("APPROVED");
    }
}
