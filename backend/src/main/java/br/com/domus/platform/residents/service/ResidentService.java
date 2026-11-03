package br.com.domus.platform.residents.service;

import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.shared.ResidentAccessRevoker;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import br.com.domus.platform.shared.UnitAssetMover;
import br.com.domus.platform.residents.repository.PetRepository;
import br.com.domus.platform.residents.entity.Pet;
import br.com.domus.platform.shared.error.InvalidRequestException;
import java.util.List;
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
    private final PetRepository pets;
    private final UnitAssetMover assets;

    /** Construtor de ResidentService; recebe as dependências por injeção. */
    public ResidentService(ResidentRepository residents, CommercialService commercial, AccessService access,
                           AuditService audit, ResidentAccessRevoker accessRevoker, PetRepository pets,
                           UnitAssetMover assets) {
        this.pets = pets;
        this.assets = assets;
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
     * @param fullName nome completo
     * @param email e-mail
     * @param phone telefone de contato
     * @param type tipo
     * @param responsibleResidentId adulto responsável, só para dependente (opcional)
     */
    @Transactional
    public Resident create(UUID condominiumId, UUID unitId, String fullName, String email, String phone,
                           ResidentType type, UUID responsibleResidentId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.create");
        commercial.requireUnit(condominiumId, unitId);
        Resident resident = new Resident(condominiumId, unitId, fullName, email, phone, type);
        resident.linkResponsible(validResponsible(condominiumId, unitId, type, responsibleResidentId, null));
        resident = residents.save(resident);
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
                           ResidentType type, boolean active, UUID responsibleResidentId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.update");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        boolean deactivated = resident.isActive() && !active;
        // decisão do titular: só adultos usam o app; quem passa a dependente perde o acesso que tinha
        boolean becameDependent = type == ResidentType.DEPENDENT && resident.getResidentType() != ResidentType.DEPENDENT;
        resident.update(fullName.trim(), email, phone, type, active);
        resident.linkResponsible(validResponsible(condominiumId, resident.getUnitId(), type, responsibleResidentId, residentId));
        if (deactivated || becameDependent) {
            accessRevoker.revokeResidentAccess(actor.userId(), condominiumId, residentId);
        }
        audit.record(actor.userId(), null, condominiumId, "resident.update", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return resident;
    }

    /** Resultado de uma mudança de unidade. */
    public record Moved(Resident resident, int dependentsMoved, int invitationsRevoked, int vehiclesMoved, int petsMoved) {
    }

    /**
     * Muda um morador de unidade. Só gestão (síndico ou administração) e com motivo. O cadastro, o acesso ao app, os meios de acesso,
     * a foto e o histórico de passagens acompanham o morador. Veículos e animais ficam na unidade antiga (não têm dono individual) e os
     * convites ativos que ele criou para ela são revogados. Os dependentes da unidade antiga só vão junto se pedido.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @param toUnitId unidade de destino, do mesmo condomínio
     * @param reason motivo, registrado na auditoria (sem dado pessoal)
     * @param moveDependents verdadeiro para levar também os dependentes ativos da unidade antiga que são dele (ligados a ele como
     *                       responsável) ou que não têm responsável definido; os ligados a outro adulto ficam
     * @param vehicleIds veículos da unidade antiga a levar junto (opcional)
     * @param petIds animais da unidade antiga a levar junto (opcional)
     */
    @Transactional
    public Moved move(UUID condominiumId, UUID residentId, UUID toUnitId, String reason, boolean moveDependents,
                      List<UUID> vehicleIds, List<UUID> petIds) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "resident.move");
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        if (!resident.isActive()) {
            throw new ConflictException("Resident is inactive");
        }
        commercial.requireUnit(condominiumId, toUnitId);
        UUID fromUnitId = resident.getUnitId();
        if (fromUnitId.equals(toUnitId)) {
            throw new ConflictException("Resident is already in this unit");
        }
        List<UUID> vehicleList = vehicleIds == null ? List.of() : vehicleIds;
        List<UUID> petList = petIds == null ? List.of() : petIds;
        resident.moveTo(toUnitId);
        int dependents = 0;
        int unlinked = 0;
        if (resident.getResidentType() != ResidentType.DEPENDENT) {
            for (Resident d : residents.findByCondominiumIdAndUnitIdAndActiveTrueOrderByFullName(condominiumId, fromUnitId)) {
                if (d.getResidentType() != ResidentType.DEPENDENT || d.getId().equals(resident.getId())) {
                    continue;
                }
                boolean mine = residentId.equals(d.getResponsibleResidentId());
                if (moveDependents && (mine || d.getResponsibleResidentId() == null)) {
                    d.moveTo(toUnitId);
                    if (mine) {
                        d.linkResponsible(residentId);
                    }
                    dependents++;
                } else if (mine) {
                    // ficou na unidade antiga: já não tem o adulto responsável por perto
                    d.linkResponsible(null);
                    unlinked++;
                }
            }
        }
        int vehiclesMoved = assets.moveVehicles(actor.userId(), condominiumId, fromUnitId, toUnitId, vehicleList);
        int petsMoved = movePets(actor.userId(), condominiumId, fromUnitId, toUnitId, petList);
        int invitations = accessRevoker.revokeResidentInvitations(actor.userId(), condominiumId, residentId, fromUnitId);
        audit.record(actor.userId(), null, condominiumId, "resident.move", "resident", residentId.toString(),
                AuditOutcome.SUCCESS, "unit " + fromUnitId + " -> " + toUnitId + "; dependents moved: " + dependents
                        + "; dependents left unlinked: " + unlinked + "; vehicles moved: " + vehiclesMoved + "; pets moved: "
                        + petsMoved + "; invitations revoked: " + invitations + "; reason: " + reason);
        return new Moved(resident, dependents, invitations, vehiclesMoved, petsMoved);
    }

    /**
     * Consulta um morador do condomínio. A equipe (portaria, síndico, administrador) vê qualquer morador do
     * condomínio; o morador vê somente o próprio cadastro.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    @Transactional
    public Resident get(UUID condominiumId, UUID residentId) {
        Actor actor = access.currentActor();
        if (!access.levelFor(actor, condominiumId).atLeast(AccessLevel.READ)) {
            ResidentContext me = access.requireResident(condominiumId, "resident.read");
            if (!me.residentId().equals(residentId)) {
                audit.record(actor.userId(), null, condominiumId, "resident.read", "resident",
                        residentId.toString(), AuditOutcome.DENIED);
                throw new NotFoundException("Resident not found");
            }
        }
        Resident resident = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Resident not found"));
        audit.record(actor.userId(), null, condominiumId, "resident.read", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
        return resident;
    }

    private int movePets(UUID actorUserId, UUID condominiumId, UUID fromUnitId, UUID toUnitId, List<UUID> petIds) {
        java.util.Set<UUID> wanted = new java.util.HashSet<>(petIds);
        if (wanted.isEmpty()) {
            return 0;
        }
        List<Pet> found = pets.findByCondominiumIdAndUnitIdAndIdInAndActiveTrue(condominiumId, fromUnitId, wanted);
        if (found.size() != wanted.size()) {
            throw new NotFoundException("Pet not found in the old unit");
        }
        for (Pet p : found) {
            p.moveTo(toUnitId);
            audit.record(actorUserId, null, condominiumId, "pet.move", "pet", p.getId().toString(), AuditOutcome.SUCCESS,
                    "unit " + fromUnitId + " -> " + toUnitId);
        }
        return found.size();
    }

    /** O responsável só vale para dependente e precisa ser um adulto ativo da mesma unidade; para outros tipos fica nulo. */
    private UUID validResponsible(UUID condominiumId, UUID unitId, ResidentType type, UUID responsibleId, UUID selfId) {
        if (type != ResidentType.DEPENDENT || responsibleId == null) {
            return null;
        }
        Resident adult = residents.findByIdAndCondominiumId(responsibleId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Responsible adult not found"));
        if (!adult.isActive() || adult.getResidentType() == ResidentType.DEPENDENT || !adult.getUnitId().equals(unitId)
                || adult.getId().equals(selfId)) {
            throw new InvalidRequestException("The responsible must be an active adult of the same unit");
        }
        return adult.getId();
    }
}
