package br.com.domus.platform.residents.service;

import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.shared.ResidentAccessRevoker;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
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
    private final ResidentAccessRevoker accessRevoker;

    /** Construtor de ResidentService; recebe as dependências por injeção. */
    public ResidentService(ResidentRepository residents, CommercialService commercial, AccessService access,
                           AuditService audit, ResidentAccessRevoker accessRevoker) {
        this.accessRevoker = accessRevoker;
        this.residents = residents;
        this.commercial = commercial;
        this.access = access;
        this.audit = audit;
    }

    /**
     * Cadastra um morador na unidade.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     * @param fullName full name
     * @param email e-mail
     * @param phone telefone de contato
     * @param type tipo
     */
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

    /**
     * Lista os moradores do condomínio.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional
    public Page<Resident> list(UUID condominiumId, UUID unitId, Pageable pageable) {
        Actor actor = access.require(condominiumId, AccessLevel.READ, "resident.list");
        audit.record(actor.userId(), null, condominiumId, "resident.list", "resident", null, AuditOutcome.SUCCESS);
        return unitId == null ? residents.findByCondominiumId(condominiumId, pageable)
                : residents.findByCondominiumIdAndUnitId(condominiumId, unitId, pageable);
    }

    /** A administração corrige o cadastro e pode desativar quem se mudou (a unidade não muda: crie outro cadastro). */
    @Transactional
    public Resident update(UUID condominiumId, UUID residentId, String fullName, String email, String phone,
                           ResidentType type, boolean active) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.update");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        boolean deactivated = resident.isActive() && !active;
        // decisão do titular: só adultos usam o app; quem passa a dependente perde o acesso que tinha
        boolean becameDependent = type == ResidentType.DEPENDENT && resident.getResidentType() != ResidentType.DEPENDENT;
        resident.update(fullName.trim(), email, phone, type, active);
        if (deactivated || becameDependent) {
            accessRevoker.revokeResidentAccess(actor.userId(), condominiumId, residentId);
        }
        audit.record(actor.userId(), null, condominiumId, "resident.update", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return resident;
    }

    /**
     * Consulta um morador do condomínio.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    @Transactional
    public Resident get(UUID condominiumId, UUID residentId) {
        Actor actor = access.require(condominiumId, AccessLevel.READ, "resident.read");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        audit.record(actor.userId(), null, condominiumId, "resident.read", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return resident;
    }
}
