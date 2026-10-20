package br.com.portaria.platform.residents;

import br.com.portaria.platform.commercial.CommercialService;
import br.com.portaria.platform.shared.audit.AuditOutcome;
import br.com.portaria.platform.shared.audit.AuditService;
import br.com.portaria.platform.shared.error.NotFoundException;
import br.com.portaria.platform.shared.tenant.AccessLevel;
import br.com.portaria.platform.shared.tenant.AccessService;
import br.com.portaria.platform.shared.tenant.Actor;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de gestão de moradores. */
@Service
public class ResidentService {

    private final ResidentRepository residents;
    private final CommercialService commercial;
    private final AccessService access;
    private final AuditService audit;

    public ResidentService(ResidentRepository residents, CommercialService commercial, AccessService access,
                           AuditService audit) {
        this.residents = residents;
        this.commercial = commercial;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public Resident create(UUID condominiumId, UUID unitId, String fullName, String email, String phone,
                           ResidentType type) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.create");
        commercial.requireUnit(condominiumId, unitId);
        Resident resident = residents.save(new Resident(condominiumId, unitId, fullName, email, phone, type));
        audit.record(actor.userId(), null, condominiumId, "resident.create", "resident",
                resident.getId().toString(), AuditOutcome.SUCCESS);
        return resident;
    }

    @Transactional(readOnly = true)
    public Page<Resident> list(UUID condominiumId, Pageable pageable) {
        Actor actor = access.require(condominiumId, AccessLevel.READ, "resident.list");
        audit.record(actor.userId(), null, condominiumId, "resident.list", "resident", null, AuditOutcome.SUCCESS);
        return residents.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional(readOnly = true)
    public Resident get(UUID condominiumId, UUID residentId) {
        Actor actor = access.require(condominiumId, AccessLevel.READ, "resident.read");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        audit.record(actor.userId(), null, condominiumId, "resident.read", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return resident;
    }
}
