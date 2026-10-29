package br.com.domus.platform.residents.service;

import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A família da unidade, pelo próprio morador: cônjuge e dependentes. Eles entram no cadastro sem acesso ao app (o
 * acesso é concedido pela administração). Proprietário e locatário só mudam pela administração.
 */
@Service
public class HouseholdService {

    /** Limite de pessoas ativas por unidade, para evitar cadastro em massa. */
    static final int MAX_PER_UNIT = 20;
    private static final Set<ResidentType> SELF_MANAGED = Set.of(ResidentType.SPOUSE, ResidentType.DEPENDENT);

    private final ResidentRepository residents;
    private final AccessService access;
    private final AuditService audit;

    /** Construtor de HouseholdService; recebe as dependências por injeção. */
    public HouseholdService(ResidentRepository residents, AccessService access, AuditService audit) {
        this.residents = residents;
        this.access = access;
        this.audit = audit;
    }

    /**
     * Lista os integrantes da unidade do morador.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    @Transactional(readOnly = true)
    public List<Resident> list(UUID condominiumId) {
        ResidentContext me = access.requireResident(condominiumId, "household.list");
        return residents.findByCondominiumIdAndUnitIdAndActiveTrueOrderByFullName(condominiumId, unitOf(me));
    }

    /** Cadastro do próprio morador autenticado, para distinguir "você" na lista. */
    @Transactional(readOnly = true)
    public UUID selfId(UUID condominiumId) {
        return access.requireResident(condominiumId, "household.list").residentId();
    }

    /**
     * Cadastra um integrante da família.
     *
     * @param condominiumId condomínio
     * @param fullName full name
     * @param relationship parentesco com o titular
     * @param phone telefone de contato
     */
    @Transactional
    public Resident add(UUID condominiumId, String fullName, ResidentType relationship, String phone) {
        ResidentContext me = access.requireResident(condominiumId, "household.add");
        if (!SELF_MANAGED.contains(relationship)) {
            throw new InvalidRequestException("Relationship must be SPOUSE or DEPENDENT");
        }
        UUID unitId = unitOf(me);
        if (residents.findByCondominiumIdAndUnitIdAndActiveTrueOrderByFullName(condominiumId, unitId).size()
                >= MAX_PER_UNIT) {
            throw new ConflictException("Household limit reached");
        }
        Resident member = residents.save(new Resident(condominiumId, unitId, fullName.trim(), null,
                phone == null || phone.isBlank() ? null : phone.trim(), relationship));
        audit.record(me.actor().userId(), null, condominiumId, "household.add", "resident",
                member.getId().toString(), AuditOutcome.SUCCESS);
        return member;
    }

    /**
     * Remove um integrante da família.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    @Transactional
    public void remove(UUID condominiumId, UUID residentId) {
        ResidentContext me = access.requireResident(condominiumId, "household.remove");
        Resident member = residents.findByIdAndCondominiumId(residentId, condominiumId)
                .filter(r -> r.isActive() && r.getUnitId().equals(unitOf(me)) && SELF_MANAGED.contains(r.getResidentType())
                        && !r.getId().equals(me.residentId()))
                .orElseThrow(() -> new NotFoundException("Household member not found"));
        member.update(member.getFullName(), member.getEmail(), member.getPhone(), member.getResidentType(), false);
        audit.record(me.actor().userId(), null, condominiumId, "household.remove", "resident",
                residentId.toString(), AuditOutcome.SUCCESS);
    }

    private UUID unitOf(ResidentContext me) {
        return residents.findByIdAndCondominiumId(me.residentId(), me.condominiumId()).orElseThrow().getUnitId();
    }
}
