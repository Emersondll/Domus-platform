package br.com.domus.platform;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.domus.platform.shared.observability.BusinessMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Os medidores de negócio refletem o banco (regressão: a consulta já ficou sem chamar next() e nunca atualizava). */
class BusinessMetricsIT extends AbstractIntegrationTest {

    @Autowired MeterRegistry registry;
    @Autowired BusinessMetrics metrics;
    @Autowired JdbcTemplate jdbc;

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    void gaugesFollowTheDatabaseAfterARefresh() {
        UUID company = UUID.randomUUID();
        UUID condo = UUID.randomUUID();
        jdbc.update("insert into operator_company (id, name, tax_id) values (?, 'M Co', ?)", company, "m-" + company.toString().substring(0, 8));
        jdbc.update("insert into condominium (id, operator_company_id, name) values (?, ?, 'M Condo')", condo, company);
        metrics.refresh();
        double queuedBefore = gauge("portaria.calls.queued");
        double conflictsBefore = gauge("portaria.sync.conflicts.open");

        for (int i = 0; i < 3; i++) {
            jdbc.update("insert into service_call (id, condominium_id, source, status, created_at) values (?, ?, 'MANUAL', 'QUEUED', now())",
                    UUID.randomUUID(), condo);
        }
        UUID agent = UUID.randomUUID();
        jdbc.update("insert into agent (id, condominium_id, name, key_hash, backlog) values (?, ?, 'm-agent', 'h', 7)", agent, condo);
        UUID event = UUID.randomUUID();
        jdbc.update("insert into agent_event (event_id, agent_id, condominium_id, type, occurred_at, received_at) values (?, ?, ?, 'DOOR_OPENED', now(), now())", event, agent, condo);
        jdbc.update("insert into sync_conflict (id, event_id, condominium_id, kind, created_at) values (?, ?, ?, 'UNCERTAIN_ENTRY', now())", UUID.randomUUID(), event, condo);

        metrics.refresh();

        assertThat(gauge("portaria.calls.queued")).isEqualTo(queuedBefore + 3);
        assertThat(gauge("portaria.sync.conflicts.open")).isEqualTo(conflictsBefore + 1);
        assertThat(gauge("portaria.agents.backlog")).isGreaterThanOrEqualTo(7);
        assertThat(gauge("portaria.agents.offline")).isGreaterThanOrEqualTo(1); // agente nunca visto
    }
}
