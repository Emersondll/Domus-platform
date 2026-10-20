package br.com.portaria.platform.shared.audit;

import br.com.portaria.platform.shared.config.CorrelationIdFilter;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Registro de auditoria com duas rotas, escolhidas por desenho:
 * <ul>
 *   <li><b>SUCCESS</b> grava na MESMA transação da ação (mesma conexão): o registro existe se, e somente se, a ação
 *       foi confirmada. Se a auditoria falhar, a ação falha. Exige transação de escrita (não {@code readOnly}).</li>
 *   <li><b>DENIED/FAILURE</b> grava por um pool pequeno e DEDICADO, fora da transação do chamador: o registro sobrevive
 *       ao rollback e, sobretudo, não disputa conexões com o pool principal. (Uma segunda conexão do mesmo pool
 *       dentro da transação — REQUIRES_NEW — esgota o pool sob concorrência: foi o defeito achado no teste de carga.)</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final String INSERT = """
            INSERT INTO audit_log (id, occurred_at, actor_user_id, operator_company_id, condominium_id, action,
                                   resource_type, resource_id, outcome, correlation_id)
            VALUES (:id, :at, :actor, :company, :condo, :action, :rtype, :rid, :outcome, :corr)
            """;

    private final JdbcClient inTransaction;
    private final HikariDataSource isolatedPool;
    private final JdbcClient isolated;

    public AuditService(DataSource mainDataSource,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${portaria.audit.pool-size:3}") int poolSize) {
        this.inTransaction = JdbcClient.create(mainDataSource);
        this.isolatedPool = new HikariDataSource();
        isolatedPool.setPoolName("audit-pool");
        isolatedPool.setJdbcUrl(url);
        isolatedPool.setUsername(username);
        isolatedPool.setPassword(password);
        isolatedPool.setMaximumPoolSize(poolSize);
        isolatedPool.setMinimumIdle(0);
        isolatedPool.setConnectionTimeout(5_000);
        isolatedPool.setInitializationFailTimeout(-1); // não impede a subida se o banco ainda estiver iniciando
        this.isolated = JdbcClient.create(isolatedPool);
    }

    public void record(UUID actorUserId, UUID operatorCompanyId, UUID condominiumId, String action,
                       String resourceType, String resourceId, AuditOutcome outcome) {
        boolean sameTransaction = outcome == AuditOutcome.SUCCESS;
        try {
            (sameTransaction ? inTransaction : isolated).sql(INSERT)
                    .param("id", UUID.randomUUID())
                    .param("at", java.sql.Timestamp.from(Instant.now()))
                    .param("actor", actorUserId)
                    .param("company", operatorCompanyId)
                    .param("condo", condominiumId)
                    .param("action", action)
                    .param("rtype", resourceType)
                    .param("rid", resourceId)
                    .param("outcome", outcome.name())
                    .param("corr", MDC.get(CorrelationIdFilter.MDC_KEY))
                    .update();
        } catch (RuntimeException e) {
            if (sameTransaction) {
                throw e; // auditoria de sucesso obrigatória: sem ela, a ação não se confirma
            }
            // Negação/falha: o 403/404 já está decidido; não mascaramos a resposta, mas o erro precisa aparecer.
            log.error("AUDIT WRITE FAILED for action={} outcome={}: {}", action, outcome, e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        isolatedPool.close();
    }
}
