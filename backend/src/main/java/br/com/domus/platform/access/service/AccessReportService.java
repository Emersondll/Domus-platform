package br.com.domus.platform.access.service;

import br.com.domus.platform.access.entity.AccessAttempt;
import br.com.domus.platform.access.entity.AccessCredential;
import br.com.domus.platform.access.entity.Visitor;
import br.com.domus.platform.access.repository.AccessAttemptRepository;
import br.com.domus.platform.access.repository.AccessCredentialRepository;
import br.com.domus.platform.access.repository.VisitorRepository;
import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relatório de acessos, visitantes no local e registro de saída. A exportação CSV contém dado pessoal (nome do
 * visitante): é limitada em período e linhas e fica registrada na auditoria.
 */
@Service
public class AccessReportService {

    /** Período máximo de uma consulta ou exportação. */
    static final Duration MAX_PERIOD = Duration.ofDays(31);
    /** Teto de linhas por exportação; acima disso o operador deve reduzir o período. */
    static final int MAX_EXPORT_ROWS = 5000;

    /**
     * Linha do relatório.
     *
     * @param unitLabel "Bloco / número" da unidade visitada, quando a credencial a identifica
     */
    public record Row(UUID attemptId, Instant occurredAt, String entrance, String result, String reason,
                      UUID unitId, String unitLabel, String visitorName, Instant exitedAt) {
    }

    private final AccessAttemptRepository attempts;
    private final AccessCredentialRepository credentials;
    private final VisitorRepository visitors;
    private final UnitRepository units;
    private final BlockRepository blocks;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public AccessReportService(AccessAttemptRepository attempts, AccessCredentialRepository credentials,
                               VisitorRepository visitors, UnitRepository units, BlockRepository blocks,
                               AccessService access, AuditService audit, Clock clock) {
        this.attempts = attempts;
        this.credentials = credentials;
        this.visitors = visitors;
        this.units = units;
        this.blocks = blocks;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<Row> onSite(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "access.onsite.list");
        Page<AccessAttempt> page = attempts.findOnSite(condominiumId, pageable);
        return page.map(rowMapper(condominiumId, page.getContent()));
    }

    @Transactional(readOnly = true)
    public Page<Row> report(UUID condominiumId, Instant from, Instant to, String result, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "access.report");
        validatePeriod(from, to);
        Page<AccessAttempt> page = attempts.findInPeriod(condominiumId, from, to, normalize(result), pageable);
        return page.map(rowMapper(condominiumId, page.getContent()));
    }

    /** Exporta o período em CSV. Grava auditoria de sucesso na mesma transação (por isso não é somente leitura). */
    @Transactional
    public String exportCsv(UUID condominiumId, Instant from, Instant to, String result) {
        Actor actor = access.require(condominiumId, AccessLevel.READ, "access.report.export");
        validatePeriod(from, to);
        Page<AccessAttempt> page = attempts.findInPeriod(condominiumId, from, to, normalize(result),
                PageRequest.of(0, MAX_EXPORT_ROWS + 1, Sort.by(Sort.Direction.ASC, "occurredAt")));
        if (page.getContent().size() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException("Too many rows; narrow the period (limit " + MAX_EXPORT_ROWS + ")");
        }
        List<Row> rows = page.getContent().stream().map(rowMapper(condominiumId, page.getContent())).toList();
        audit.record(actor.userId(), null, condominiumId, "access.report.export", "access_attempt", null,
                AuditOutcome.SUCCESS);
        StringBuilder csv = new StringBuilder("data_hora,entrada,resultado,motivo,unidade,visitante,saida\r\n");
        for (Row r : rows) {
            csv.append(String.join(",", cell(r.occurredAt()), cell(r.entrance()), cell(r.result()), cell(r.reason()),
                    cell(r.unitLabel()), cell(r.visitorName()), cell(r.exitedAt()))).append("\r\n");
        }
        return csv.toString();
    }

    @Transactional
    public Row registerExit(UUID condominiumId, UUID attemptId) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "access.exit");
        AccessAttempt attempt = attempts.findByIdAndCondominiumId(attemptId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Access entry not found"));
        if (!"GRANTED".equals(attempt.getResult())) {
            throw new ConflictException("Only a granted entry can have an exit");
        }
        if (attempt.getExitedAt() != null) {
            throw new ConflictException("Exit already registered");
        }
        attempt.registerExit(actor.userId(), Instant.now(clock));
        audit.record(actor.userId(), null, condominiumId, "access.exit", "access_attempt", attemptId.toString(),
                AuditOutcome.SUCCESS);
        return rowMapper(condominiumId, List.of(attempt)).apply(attempt);
    }

    private static void validatePeriod(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException("Period must be positive and at most 31 days");
        }
    }

    private static String normalize(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        if (!Set.of("GRANTED", "DENIED").contains(result)) {
            throw new IllegalArgumentException("result must be GRANTED or DENIED");
        }
        return result;
    }

    /** Resolve credencial, visitante e unidade em lote (sempre escopado ao condomínio). */
    private java.util.function.Function<AccessAttempt, Row> rowMapper(UUID condominiumId, List<AccessAttempt> batch) {
        Set<UUID> credentialIds = batch.stream().map(AccessAttempt::getCredentialId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, AccessCredential> byCredential = new HashMap<>();
        credentials.findAllById(credentialIds).stream().filter(c -> c.getCondominiumId().equals(condominiumId))
                .forEach(c -> byCredential.put(c.getId(), c));
        Set<UUID> visitorIds = byCredential.values().stream().map(AccessCredential::getVisitorId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Visitor> byVisitor = new HashMap<>();
        visitors.findAllById(visitorIds).stream().filter(v -> v.getCondominiumId().equals(condominiumId))
                .forEach(v -> byVisitor.put(v.getId(), v));
        Map<UUID, String> unitLabels = unitLabels(condominiumId,
                byCredential.values().stream().map(AccessCredential::getUnitId).collect(Collectors.toSet()));
        return a -> {
            AccessCredential c = byCredential.get(a.getCredentialId());
            Visitor v = c == null || c.getVisitorId() == null ? null : byVisitor.get(c.getVisitorId());
            UUID unitId = c == null ? null : c.getUnitId();
            return new Row(a.getId(), a.getOccurredAt(), a.getEntrance(), a.getResult(), a.getReason(), unitId,
                    unitId == null ? null : unitLabels.get(unitId), v == null ? null : v.getFullName(),
                    a.getExitedAt());
        };
    }

    private Map<UUID, String> unitLabels(UUID condominiumId, Set<UUID> unitIds) {
        Map<UUID, String> labels = new HashMap<>();
        List<Unit> found = units.findAllById(unitIds).stream().filter(u -> u.getCondominiumId().equals(condominiumId))
                .toList();
        Map<UUID, String> blockNames = new HashMap<>();
        blocks.findAllById(found.stream().map(Unit::getBlockId).collect(Collectors.toSet())).stream()
                .filter(b -> b.getCondominiumId().equals(condominiumId))
                .forEach((Block b) -> blockNames.put(b.getId(), b.getName()));
        for (Unit u : found) {
            labels.put(u.getId(), blockNames.getOrDefault(u.getBlockId(), "?") + " / " + u.getNumber());
        }
        return labels;
    }

    /**
     * Neutraliza injeção de fórmula em planilhas (valores iniciados por = + - @ tab ou CR) e aplica aspas CSV.
     */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
