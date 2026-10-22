package br.com.domus.platform.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Métricas operacionais do negócio. São medidores atualizados a cada 30 s por UMA consulta agregada: o scrape do
 * Prometheus nunca toca o banco. Sem tags por condomínio/usuário, para não explodir a cardinalidade nem expor dados.
 */
@Component
public class BusinessMetrics {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);

    private final JdbcClient jdbc;
    private final AtomicLong queuedCalls = new AtomicLong();
    private final AtomicLong assignedCalls = new AtomicLong();
    private final AtomicLong openIncidents = new AtomicLong();
    private final AtomicLong agentsOffline = new AtomicLong();
    private final AtomicLong agentBacklog = new AtomicLong();
    private final AtomicLong openConflicts = new AtomicLong();
    private final AtomicLong devicesOffline = new AtomicLong();
    private final AtomicLong commandsPending = new AtomicLong();
    private final AtomicLong commandsUnknown1h = new AtomicLong();
    private final AtomicLong commandsFailed1h = new AtomicLong();

    public BusinessMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.jdbc = jdbc;
        gauge(registry, "portaria.calls.queued", "Atendimentos aguardando operador", queuedCalls);
        gauge(registry, "portaria.calls.assigned", "Atendimentos em andamento", assignedCalls);
        gauge(registry, "portaria.incidents.open", "Incidentes abertos", openIncidents);
        gauge(registry, "portaria.agents.offline", "Agentes ativos sem contato há mais de 90 s", agentsOffline);
        gauge(registry, "portaria.agents.backlog", "Eventos ainda não sincronizados nos agentes", agentBacklog);
        gauge(registry, "portaria.sync.conflicts.open", "Conflitos de sincronização sem resolução", openConflicts);
        gauge(registry, "portaria.devices.offline", "Dispositivos reportados offline", devicesOffline);
        gauge(registry, "portaria.commands.pending", "Comandos físicos aguardando confirmação", commandsPending);
        gauge(registry, "portaria.commands.unknown.1h", "Comandos físicos sem confirmação na última hora", commandsUnknown1h);
        gauge(registry, "portaria.commands.failed.1h", "Comandos físicos que falharam na última hora", commandsFailed1h);
    }

    /** Primeira leitura só depois que a aplicação está pronta (as migrações já rodaram). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    private static void gauge(MeterRegistry registry, String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).description(description).register(registry);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refresh() {
        try {
            jdbc.sql("""
                    SELECT
                      (SELECT count(*) FROM service_call WHERE status = 'QUEUED') AS queued,
                      (SELECT count(*) FROM service_call WHERE status = 'ASSIGNED') AS assigned,
                      (SELECT count(*) FROM incident WHERE status = 'OPEN') AS incidents,
                      (SELECT count(*) FROM agent WHERE status = 'ACTIVE'
                         AND (last_seen_at IS NULL OR last_seen_at < now() - interval '90 seconds')) AS agents_off,
                      (SELECT COALESCE(sum(backlog), 0) FROM agent WHERE status = 'ACTIVE') AS backlog,
                      (SELECT count(*) FROM sync_conflict WHERE resolved_at IS NULL) AS conflicts,
                      (SELECT count(*) FROM device WHERE status = 'OFFLINE') AS dev_off,
                      (SELECT count(*) FROM access_command WHERE state IN ('PENDING', 'SENT')) AS pending,
                      (SELECT count(*) FROM access_command WHERE state = 'UNKNOWN'
                         AND requested_at > now() - interval '1 hour') AS unknown,
                      (SELECT count(*) FROM access_command WHERE state = 'FAILED'
                         AND requested_at > now() - interval '1 hour') AS failed
                    """).query((rs, row) -> {
                queuedCalls.set(rs.getLong("queued"));
                assignedCalls.set(rs.getLong("assigned"));
                openIncidents.set(rs.getLong("incidents"));
                agentsOffline.set(rs.getLong("agents_off"));
                agentBacklog.set(rs.getLong("backlog"));
                openConflicts.set(rs.getLong("conflicts"));
                devicesOffline.set(rs.getLong("dev_off"));
                commandsPending.set(rs.getLong("pending"));
                commandsUnknown1h.set(rs.getLong("unknown"));
                commandsFailed1h.set(rs.getLong("failed"));
                return row;
            }).single();
        } catch (RuntimeException e) {
            log.warn("Could not refresh business metrics: {}", e.getMessage());
        }
    }
}
