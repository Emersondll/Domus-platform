package br.com.domus.platform.operations.service;

import br.com.domus.platform.operations.entity.Shift;
import br.com.domus.platform.operations.entity.ShiftLogEntry;
import br.com.domus.platform.operations.repository.ShiftLogEntryRepository;
import br.com.domus.platform.operations.repository.ShiftRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
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

/**
 * Livro de turno: registros livres e passagens de turno da portaria, por condomínio. Quem escreve precisa operar o
 * condomínio; o registro guarda o turno aberto do autor (se houver) para a leitura posterior.
 */
@Service
public class ShiftLogService {

    private final ShiftLogEntryRepository entries;
    private final ShiftRepository shifts;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public ShiftLogService(ShiftLogEntryRepository entries, ShiftRepository shifts, AccessService access,
                           AuditService audit, Clock clock) {
        this.entries = entries;
        this.shifts = shifts;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ShiftLogEntry write(UUID condominiumId, String kind, String text) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "shiftlog.write");
        UUID shiftId = shifts.findByUserIdAndEndedAtIsNull(actor.userId()).map(Shift::getId).orElse(null);
        ShiftLogEntry entry = entries.save(new ShiftLogEntry(condominiumId, shiftId, actor.userId(), kind,
                text.trim(), Instant.now(clock)));
        audit.record(actor.userId(), null, condominiumId, "shiftlog.write", "shift_log_entry",
                entry.getId().toString(), AuditOutcome.SUCCESS);
        return entry;
    }

    @Transactional(readOnly = true)
    public Page<ShiftLogEntry> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "shiftlog.list");
        return entries.findByCondominiumId(condominiumId, pageable);
    }
}
