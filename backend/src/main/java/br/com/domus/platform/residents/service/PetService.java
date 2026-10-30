package br.com.domus.platform.residents.service;

import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.residents.entity.Pet;
import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.repository.PetRepository;
import br.com.domus.platform.residents.repository.ResidentRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import br.com.domus.platform.shared.tenant.ResidentContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Animais de estimação: a administração cadastra por unidade; o morador cuida dos animais da própria unidade. */
@Service
public class PetService {

    /** Limite por unidade, para evitar cadastro em massa. */
    static final int MAX_PER_UNIT = 10;

    private final PetRepository pets;
    private final ResidentRepository residents;
    private final CommercialService commercial;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    /** Construtor de PetService; recebe as dependências por injeção. */
    public PetService(PetRepository pets, ResidentRepository residents, CommercialService commercial,
                      AccessService access, AuditService audit, Clock clock) {
        this.pets = pets;
        this.residents = residents;
        this.commercial = commercial;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Cadastra um animal da unidade.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     * @param name nome
     * @param species espécie do animal
     * @param breed raça do animal
     * @param notes observações livres
     */
    @Transactional
    public Pet register(UUID condominiumId, UUID unitId, String name, String species, String breed, String notes) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "pet.register");
        commercial.requireUnit(condominiumId, unitId);
        return create(actor.userId(), condominiumId, unitId, name, species, breed, notes, "pet.register");
    }

    /**
     * Lista os animais do condomínio.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<Pet> list(UUID condominiumId, UUID unitId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "pet.list");
        return unitId == null ? pets.findByCondominiumIdAndActiveTrue(condominiumId, pageable)
                : pets.findByCondominiumIdAndUnitIdAndActiveTrue(condominiumId, unitId, pageable);
    }

    /**
     * Atualiza um animal.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     * @param name nome
     * @param species espécie do animal
     * @param breed raça do animal
     * @param notes observações livres
     */
    @Transactional
    public Pet update(UUID condominiumId, UUID petId, String name, String species, String breed, String notes) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "pet.update");
        Pet pet = find(condominiumId, petId);
        pet.update(name.trim(), species, blankToNull(breed), blankToNull(notes));
        audit.record(actor.userId(), null, condominiumId, "pet.update", "pet", petId.toString(), AuditOutcome.SUCCESS);
        return pet;
    }

    /**
     * Remove um animal.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     */
    @Transactional
    public void remove(UUID condominiumId, UUID petId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "pet.remove");
        find(condominiumId, petId).deactivate();
        audit.record(actor.userId(), null, condominiumId, "pet.remove", "pet", petId.toString(), AuditOutcome.SUCCESS);
    }

    /**
     * Lista os animais da unidade do morador autenticado.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    @Transactional(readOnly = true)
    public List<Pet> listMine(UUID condominiumId) {
        UUID unitId = myUnit(access.requireResident(condominiumId, "pet.list.self"));
        return pets.findByCondominiumIdAndUnitIdAndActiveTrueOrderByName(condominiumId, unitId);
    }

    /**
     * Cadastra um animal na unidade do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param name nome
     * @param species espécie do animal
     * @param breed raça do animal
     * @param notes observações livres
     */
    @Transactional
    public Pet registerMine(UUID condominiumId, String name, String species, String breed, String notes) {
        ResidentContext me = access.requireResident(condominiumId, "pet.register.self");
        return create(me.actor().userId(), condominiumId, myUnit(me), name, species, breed, notes,
                "pet.register.self");
    }

    /**
     * Remove um animal da unidade do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     */
    @Transactional
    public void removeMine(UUID condominiumId, UUID petId) {
        ResidentContext me = access.requireResident(condominiumId, "pet.remove.self");
        Pet pet = find(condominiumId, petId);
        if (!pet.getUnitId().equals(myUnit(me))) {
            throw new NotFoundException("Pet not found");
        }
        pet.deactivate();
        audit.record(me.actor().userId(), null, condominiumId, "pet.remove.self", "pet", petId.toString(),
                AuditOutcome.SUCCESS);
    }

    /**
     * Atualiza um animal da unidade do morador autenticado.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     * @param name nome
     * @param species espécie do animal
     * @param breed raça do animal
     * @param notes observações livres
     */
    @Transactional
    public Pet updateMine(UUID condominiumId, UUID petId, String name, String species, String breed, String notes) {
        ResidentContext me = access.requireResident(condominiumId, "pet.update.self");
        Pet pet = find(condominiumId, petId);
        if (!pet.getUnitId().equals(myUnit(me))) {
            throw new NotFoundException("Pet not found");
        }
        pet.update(name.trim(), species, blankToNull(breed), blankToNull(notes));
        audit.record(me.actor().userId(), null, condominiumId, "pet.update.self", "pet", petId.toString(),
                AuditOutcome.SUCCESS);
        return pet;
    }

    private Pet create(UUID userId, UUID condominiumId, UUID unitId, String name, String species, String breed,
                       String notes, String action) {
        if (pets.countByCondominiumIdAndUnitIdAndActiveTrue(condominiumId, unitId) >= MAX_PER_UNIT) {
            throw new ConflictException("Pet limit per unit reached");
        }
        Pet pet = pets.save(new Pet(condominiumId, unitId, name.trim(), species, blankToNull(breed),
                blankToNull(notes), Instant.now(clock)));
        audit.record(userId, null, condominiumId, action, "pet", pet.getId().toString(), AuditOutcome.SUCCESS);
        return pet;
    }

    private Pet find(UUID condominiumId, UUID petId) {
        return pets.findByIdAndCondominiumId(petId, condominiumId).filter(Pet::isActive)
                .orElseThrow(() -> new NotFoundException("Pet not found"));
    }

    private UUID myUnit(ResidentContext me) {
        Resident r = residents.findByIdAndCondominiumId(me.residentId(), me.condominiumId()).orElseThrow();
        return r.getUnitId();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
