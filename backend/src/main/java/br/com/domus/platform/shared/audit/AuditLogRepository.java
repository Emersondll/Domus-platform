package br.com.domus.platform.shared.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso ao registro de auditoria. */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    List<AuditLogEntry> findByCondominiumIdOrderByOccurredAtDesc(UUID condominiumId);
}
