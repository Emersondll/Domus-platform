package br.com.domus.platform.reports.service;

import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatório de atividade da portaria no período: encomendas, atendimentos, ocorrências e livro de turno. Só números
 * agregados (nenhum dado pessoal), sempre de um condomínio; por isso a consulta não precisa de auditoria própria.
 */
@Service
public class ActivityReportService {

    /** Período máximo de uma consulta. */
    static final Duration MAX_PERIOD = Duration.ofDays(31);

    /**
     * @param received         encomendas recebidas no período
     * @param pickedUp         encomendas retiradas no período
     * @param pendingNow       encomendas ainda a retirar hoje (independe do período)
     * @param avgHoursToPickup tempo médio, em horas, entre o recebimento e a retirada das retiradas no período; nulo se não houve
     */
    public record Parcels(long received, long pickedUp, long pendingNow, Double avgHoursToPickup) {
    }

    /**
     * @param total            atendimentos abertos no período
     * @param byOutcome        quantidade por desfecho (RESOLVED, ENTRY_GRANTED…); "OPEN" = ainda sem desfecho
     * @param avgSecondsToAnswer tempo médio de espera até um operador atender; nulo se nenhum foi atendido
     * @param avgMinutesHandling duração média do atendimento (do atendimento ao encerramento); nulo se nenhum foi encerrado
     */
    public record Calls(long total, Map<String, Long> byOutcome, Double avgSecondsToAnswer, Double avgMinutesHandling) {
    }

    /**
     * @param opened     ocorrências abertas no período
     * @param resolved   ocorrências resolvidas no período
     * @param bySeverity abertas por gravidade (LOW, MEDIUM, HIGH)
     */
    public record Incidents(long opened, long resolved, Map<String, Long> bySeverity) {
    }

    /**
     * @param notes     anotações do livro de turno no período
     * @param handovers passagens de turno no período
     */
    public record ShiftLog(long notes, long handovers) {
    }

    /** Resumo da atividade do período: só números, sem dado pessoal. */
    public record Report(Instant from, Instant to, Parcels parcels, Calls calls, Incidents incidents, ShiftLog shiftLog) {
    }

    /** Teto de unidades devolvidas no relatório por unidade (as mais ativas primeiro). */
    static final int MAX_UNITS = 500;

    /**
     * Atividade de uma unidade no período. Só contagens e o rótulo "bloco / número": nenhum nome de morador.
     *
     * @param unitId         unidade
     * @param label          rótulo "Bloco / número" da unidade
     * @param parcelsReceived encomendas recebidas para a unidade no período
     * @param calls          atendimentos abertos pela unidade no período
     */
    public record UnitActivity(UUID unitId, String label, long parcelsReceived, long calls) {
    }

    private final JdbcClient jdbc;
    private final AccessService access;
    private final AuditService audit;

    /** Construtor de ActivityReportService; recebe as dependências por injeção. */
    public ActivityReportService(JdbcClient jdbc, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /**
     * Atividade por unidade no período (encomendas recebidas e atendimentos), das mais ativas para as menos, até
     * {@value #MAX_UNITS} unidades. Exige nível MANAGE: o número por unidade descreve a rotina de uma família e fica
     * restrito a quem administra o condomínio; cada consulta é auditada.
     *
     * @param condominiumId condomínio
     * @param from início do período
     * @param to fim do período (exclusivo)
     * @return unidades com alguma atividade
     */
    @Transactional
    public List<UnitActivity> byUnit(UUID condominiumId, Instant from, Instant to) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "report.activity.by-unit");
        checkPeriod(from, to);
        Timestamp f = Timestamp.from(from);
        Timestamp t = Timestamp.from(to);
        List<UnitActivity> rows = jdbc.sql("""
                SELECT id, block, number, parcels, calls FROM (
                    SELECT u.id, b.name AS block, u.number,
                           (SELECT count(*) FROM parcel p WHERE p.unit_id = u.id AND p.condominium_id = u.condominium_id
                               AND p.received_at >= :f AND p.received_at < :t) AS parcels,
                           (SELECT count(*) FROM service_call c WHERE c.unit_id = u.id AND c.condominium_id = u.condominium_id
                               AND c.created_at >= :f AND c.created_at < :t) AS calls
                    FROM unit u JOIN block b ON b.id = u.block_id AND b.condominium_id = u.condominium_id
                    WHERE u.condominium_id = :c) per_unit
                WHERE parcels + calls > 0
                ORDER BY parcels + calls DESC, block, number
                LIMIT :max
                """).param("c", condominiumId).param("f", f).param("t", t).param("max", MAX_UNITS)
                .query((rs, n) -> new UnitActivity(rs.getObject("id", UUID.class), rs.getString("block") + " / " + rs.getString("number"),
                        rs.getLong("parcels"), rs.getLong("calls"))).list();
        audit.record(actor.userId(), null, condominiumId, "report.activity.by-unit", "report", null, AuditOutcome.SUCCESS);
        return rows;
    }

    /** CSV "unidade,encomendas_recebidas,atendimentos" do relatório por unidade (sem nomes de pessoas). */
    public String unitCsv(List<UnitActivity> units) {
        StringBuilder out = new StringBuilder("unidade,encomendas_recebidas,atendimentos\r\n");
        for (UnitActivity u : units) {
            out.append('"').append(u.label().replace("\"", "\"\"")).append("\",").append(u.parcelsReceived()).append(',')
                    .append(u.calls()).append("\r\n");
        }
        return out.toString();
    }

    private static void checkPeriod(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException("Period must be positive and at most 31 days");
        }
    }

    /**
     * Monta o resumo agregado da atividade do período (somente números, sem dado pessoal).
     *
     * @param condominiumId condomínio
     * @param from início do período
     * @param to fim do período
     */
    @Transactional(readOnly = true)
    public Report report(UUID condominiumId, Instant from, Instant to) {
        access.require(condominiumId, AccessLevel.READ, "report.activity");
        checkPeriod(from, to);
        Timestamp f = Timestamp.from(from);
        Timestamp t = Timestamp.from(to);
        Parcels parcels = jdbc.sql("""
                SELECT count(*) FILTER (WHERE received_at >= :f AND received_at < :t) AS received,
                       count(*) FILTER (WHERE picked_up_at >= :f AND picked_up_at < :t) AS picked,
                       count(*) FILTER (WHERE status = 'RECEIVED') AS pending,
                       avg(extract(epoch FROM picked_up_at - received_at)) FILTER (WHERE picked_up_at >= :f AND picked_up_at < :t) AS secs
                FROM parcel WHERE condominium_id = :c
                """).param("c", condominiumId).param("f", f).param("t", t)
                .query((rs, n) -> new Parcels(rs.getLong("received"), rs.getLong("picked"), rs.getLong("pending"),
                        avg(rs.getObject("secs"), 3600))).single();
        Map<String, Long> outcomes = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT COALESCE(outcome, 'OPEN') AS outcome, count(*) AS n FROM service_call
                WHERE condominium_id = :c AND created_at >= :f AND created_at < :t GROUP BY 1 ORDER BY 1
                """).param("c", condominiumId).param("f", f).param("t", t)
                .query((rs, n) -> outcomes.put(rs.getString("outcome"), rs.getLong("n"))).list();
        Calls calls = jdbc.sql("""
                SELECT count(*) AS total,
                       avg(extract(epoch FROM assigned_at - created_at)) FILTER (WHERE assigned_at IS NOT NULL) AS wait,
                       avg(extract(epoch FROM closed_at - assigned_at)) FILTER (WHERE closed_at IS NOT NULL AND assigned_at IS NOT NULL) AS handling
                FROM service_call WHERE condominium_id = :c AND created_at >= :f AND created_at < :t
                """).param("c", condominiumId).param("f", f).param("t", t)
                .query((rs, n) -> new Calls(rs.getLong("total"), outcomes, avg(rs.getObject("wait"), 1),
                        avg(rs.getObject("handling"), 60))).single();
        Map<String, Long> severities = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT severity, count(*) AS n FROM incident
                WHERE condominium_id = :c AND opened_at >= :f AND opened_at < :t GROUP BY 1 ORDER BY 1
                """).param("c", condominiumId).param("f", f).param("t", t)
                .query((rs, n) -> severities.put(rs.getString("severity"), rs.getLong("n"))).list();
        Incidents incidents = jdbc.sql("""
                SELECT count(*) FILTER (WHERE opened_at >= :f AND opened_at < :t) AS opened,
                       count(*) FILTER (WHERE resolved_at >= :f AND resolved_at < :t) AS resolved
                FROM incident WHERE condominium_id = :c
                """).param("c", condominiumId).param("f", f).param("t", t)
                .query((rs, n) -> new Incidents(rs.getLong("opened"), rs.getLong("resolved"), severities)).single();
        ShiftLog shiftLog = jdbc.sql("""
                SELECT count(*) FILTER (WHERE kind = 'NOTE') AS notes, count(*) FILTER (WHERE kind = 'HANDOVER') AS handovers
                FROM shift_log_entry WHERE condominium_id = :c AND created_at >= :f AND created_at < :t
                """).param("c", condominiumId).param("f", f).param("t", t)
                .query((rs, n) -> new ShiftLog(rs.getLong("notes"), rs.getLong("handovers"))).single();
        return new Report(from, to, parcels, calls, incidents, shiftLog);
    }

    /** CSV "secao,indicador,valor" do mesmo relatório (sem dados pessoais). */
    public String csv(Report r) {
        StringBuilder out = new StringBuilder("secao,indicador,valor\r\n");
        row(out, "encomendas", "recebidas", r.parcels().received());
        row(out, "encomendas", "retiradas", r.parcels().pickedUp());
        row(out, "encomendas", "a_retirar_hoje", r.parcels().pendingNow());
        row(out, "encomendas", "horas_medias_ate_retirada", r.parcels().avgHoursToPickup());
        row(out, "atendimentos", "total", r.calls().total());
        r.calls().byOutcome().forEach((k, v) -> row(out, "atendimentos", "desfecho_" + k, v));
        row(out, "atendimentos", "segundos_medios_ate_atender", r.calls().avgSecondsToAnswer());
        row(out, "atendimentos", "minutos_medios_de_atendimento", r.calls().avgMinutesHandling());
        row(out, "ocorrencias", "abertas", r.incidents().opened());
        row(out, "ocorrencias", "resolvidas", r.incidents().resolved());
        r.incidents().bySeverity().forEach((k, v) -> row(out, "ocorrencias", "gravidade_" + k, v));
        row(out, "livro_de_turno", "anotacoes", r.shiftLog().notes());
        row(out, "livro_de_turno", "passagens_de_turno", r.shiftLog().handovers());
        return out.toString();
    }

    private static void row(StringBuilder out, String section, String name, Object value) {
        out.append(section).append(',').append(name).append(',').append(value == null ? "" : value).append("\r\n");
    }

    private static Double avg(Object seconds, double divisor) {
        return seconds == null ? null : Math.round(((Number) seconds).doubleValue() / divisor * 10) / 10.0;
    }
}
