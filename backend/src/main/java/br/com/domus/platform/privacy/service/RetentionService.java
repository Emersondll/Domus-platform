package br.com.domus.platform.privacy.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expurgo periódico conforme a política de retenção ({@code portaria.retention.*}). Registros referenciados por
 * dados que ainda precisam existir (tentativas ligadas a comandos físicos, eventos ligados a conflitos abertos) são
 * preservados. O {@code audit_log} é append-only por trigger e NÃO é expurgado aqui: seu descarte exige procedimento
 * de manutenção controlado (ver docs/lgpd.md). A imagem da câmera de entrada (visit_snapshot) e o histórico de acessos do morador (resident_access_event) são expurgados aqui; áudio não é armazenado.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry meters;
    private final int notificationsDays;
    private final int accessAttemptsDays;
    private final int agentEventsDays;
    private final int deviceEventsDays;
    private final int resolvedConflictsDays;
    private final int snapshotsDays;
    private final int residentAccessDays;
    private final int directMessageDays;
    private final boolean enabled;

    public RetentionService(JdbcClient jdbc, Clock clock, MeterRegistry meters,
            @Value("${portaria.retention.enabled:true}") boolean enabled,
            @Value("${portaria.retention.notifications-days:90}") int notificationsDays,
            @Value("${portaria.retention.access-attempts-days:365}") int accessAttemptsDays,
            @Value("${portaria.retention.agent-events-days:90}") int agentEventsDays,
            @Value("${portaria.retention.device-events-days:90}") int deviceEventsDays,
            @Value("${portaria.retention.resolved-conflicts-days:365}") int resolvedConflictsDays,
            @Value("${portaria.snapshots.retention-days:30}") int snapshotsDays,
            @Value("${portaria.retention.resident-access-days:90}") int residentAccessDays,
            @Value("${portaria.retention.direct-message-days:0}") int directMessageDays) {
        this.directMessageDays = directMessageDays;
        this.jdbc = jdbc;
        this.clock = clock;
        this.meters = meters;
        this.enabled = enabled;
        this.notificationsDays = notificationsDays;
        this.accessAttemptsDays = accessAttemptsDays;
        this.agentEventsDays = agentEventsDays;
        this.deviceEventsDays = deviceEventsDays;
        this.resolvedConflictsDays = resolvedConflictsDays;
        this.snapshotsDays = snapshotsDays;
        this.residentAccessDays = residentAccessDays;
    }

    @Scheduled(cron = "${portaria.retention.cron:0 30 3 * * *}")
    public void scheduled() {
        if (enabled) {
            purge();
        }
    }

    /** @return linhas removidas por tabela */
    @Transactional
    public Map<String, Integer> purge() {
        Instant now = Instant.now(clock);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("notification", jdbc.sql("DELETE FROM notification WHERE created_at < :cutoff")
                .param("cutoff", cutoff(now, notificationsDays)).update());
        deleted.put("access_attempt", jdbc.sql("""
                DELETE FROM access_attempt WHERE occurred_at < :cutoff
                  AND id NOT IN (SELECT attempt_id FROM access_command WHERE attempt_id IS NOT NULL)
                """).param("cutoff", cutoff(now, accessAttemptsDays)).update());
        // Conflitos já resolvidos, depois os eventos de agente que não são mais referenciados por nenhum conflito.
        deleted.put("sync_conflict", jdbc.sql("DELETE FROM sync_conflict WHERE resolved_at IS NOT NULL AND resolved_at < :cutoff")
                .param("cutoff", cutoff(now, resolvedConflictsDays)).update());
        deleted.put("agent_event", jdbc.sql("""
                DELETE FROM agent_event WHERE received_at < :cutoff
                  AND event_id NOT IN (SELECT event_id FROM sync_conflict)
                """).param("cutoff", cutoff(now, agentEventsDays)).update());
        deleted.put("device_event", jdbc.sql("DELETE FROM device_event WHERE received_at < :cutoff")
                .param("cutoff", cutoff(now, deviceEventsDays)).update());
        // Imagem da câmera de entrada: a que não foi enviada some quando vence (minutos); a enviada, após o prazo de retenção.
        deleted.put("visit_snapshot", jdbc.sql("""
                DELETE FROM visit_snapshot
                WHERE (visit_request_id IS NULL AND expires_at < :now) OR captured_at < :cutoff
                """).param("now", java.sql.Timestamp.from(now)).param("cutoff", cutoff(now, snapshotsDays)).update());
        // Histórico pessoal de entradas e saídas do morador: prazo curto, por ser dado de rotina e localização.
        deleted.put("resident_access_event", jdbc.sql("DELETE FROM resident_access_event WHERE occurred_at < :cutoff")
                .param("cutoff", cutoff(now, residentAccessDays)).update());
        // Mensagens morador <-> portaria: só expurga se o prazo foi decidido (0 = desligado, nada é apagado).
        if (directMessageDays > 0) {
            deleted.put("direct_message", jdbc.sql("DELETE FROM direct_message WHERE created_at < :cutoff")
                    .param("cutoff", cutoff(now, directMessageDays)).update());
        }
        deleted.forEach((table, n) -> {
            if (n > 0) {
                meters.counter("portaria.retention.deleted", "table", table).increment(n);
            }
        });
        log.info("Retention purge finished: {}", deleted);
        return deleted;
    }

    private static java.sql.Timestamp cutoff(Instant now, int days) {
        return java.sql.Timestamp.from(now.minus(Duration.ofDays(days)));
    }
}
