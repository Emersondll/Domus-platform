package br.com.portaria.platform.shared.audit;

import br.com.portaria.platform.shared.config.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grava auditoria em transação própria: negações e falhas continuam registradas mesmo quando a transação
 * da operação de negócio sofre rollback.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorUserId, UUID operatorCompanyId, UUID condominiumId, String action,
                       String resourceType, String resourceId, AuditOutcome outcome) {
        repository.save(new AuditLogEntry(actorUserId, operatorCompanyId, condominiumId, action,
                resourceType, resourceId, outcome, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }
}
