package br.com.domus.platform.agent.service;

import br.com.domus.platform.agent.entity.SyncConflict;
import br.com.domus.platform.agent.repository.SyncConflictRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consulta e resolução dos conflitos de sincronização do agente local (escopo do condomínio). */
@Service
public class SyncConflictService {

    private final SyncConflictRepository conflicts;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    /** Construtor de SyncConflictService; recebe as dependências por injeção. */
    public SyncConflictService(SyncConflictRepository conflicts, AccessService access, AuditService audit,
                               Clock clock) {
        this.conflicts = conflicts;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Lista os conflitos do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable      paginação
     * @return página de conflitos
     */
    @Transactional(readOnly = true)
    public Page<SyncConflict> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "conflict.list");
        return conflicts.findByCondominiumId(condominiumId, pageable);
    }

    /**
     * Marca o conflito como resolvido e audita a decisão na mesma transação.
     *
     * @param condominiumId condomínio
     * @param conflictId    conflito
     * @return conflito resolvido
     * @throws NotFoundException se o conflito não existe neste condomínio
     */
    @Transactional
    public SyncConflict resolve(UUID condominiumId, UUID conflictId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "conflict.resolve");
        SyncConflict conflict = conflicts.findByIdAndCondominiumId(conflictId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Conflict not found"));
        conflict.resolve(actor.userId(), Instant.now(clock));
        audit.record(actor.userId(), null, condominiumId, "conflict.resolve", "sync_conflict",
                conflictId.toString(), AuditOutcome.SUCCESS);
        return conflict;
    }
}
