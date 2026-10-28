package br.com.domus.platform.residents.service;

import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Autoatendimento do morador: residências do usuário e atualização de contato. */
@Service
public class ResidentSelfService {

    /** Residência do usuário: condomínio, unidade e cadastro de morador. */
    public record Residency(UUID condominiumId, String condominiumName, UUID unitId, String unitNumber,
                            UUID residentId) {
    }

    private final ResidentRepository residents;
    private final CommercialService commercial;
    private final AccessService access;
    private final AuditService audit;

    /** Construtor de ResidentSelfService; recebe as dependências por injeção. */
    public ResidentSelfService(ResidentRepository residents, CommercialService commercial, AccessService access,
                               AuditService audit) {
        this.residents = residents;
        this.commercial = commercial;
        this.access = access;
        this.audit = audit;
    }

    /**
     * Lista os condomínios e unidades do morador autenticado.
     *
     * @return lista de resultados
     */
    @Transactional(readOnly = true)
    public List<Residency> residencies() {
        Actor actor = access.currentActor();
        return actor.memberships().stream()
                .filter(m -> m.getRole() == Role.RESIDENT)
                .map(this::toResidency)
                .toList();
    }

    /**
     * Consulta o perfil do morador autenticado.
     *
     * @param condominiumId condomínio
     */
    @Transactional(readOnly = true)
    public Resident profile(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "profile.read");
        return residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow();
    }

    /**
     * Atualiza o contato do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param email e-mail
     * @param phone phone
     */
    @Transactional
    public Resident updateContact(UUID condominiumId, String email, String phone) {
        ResidentContext me = access.requireResident(condominiumId, "profile.update");
        Resident resident = residents.findByIdAndCondominiumId(me.residentId(), condominiumId).orElseThrow();
        resident.updateContact(email, phone);
        audit.record(me.actor().userId(), null, condominiumId, "profile.update", "resident",
                resident.getId().toString(), AuditOutcome.SUCCESS);
        return resident;
    }

    private Residency toResidency(Membership m) {
        Resident resident = residents.findByIdAndCondominiumId(m.getResidentId(), m.getCondominiumId()).orElseThrow();
        return new Residency(m.getCondominiumId(), commercial.findCondominiumInternal(m.getCondominiumId()).getName(),
                resident.getUnitId(), commercial.requireUnit(m.getCondominiumId(), resident.getUnitId()).getNumber(),
                resident.getId());
    }
}
