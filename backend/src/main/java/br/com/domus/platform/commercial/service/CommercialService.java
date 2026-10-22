package br.com.domus.platform.commercial.service;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.ForbiddenException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de gestão comercial: empresas, condomínios, blocos e unidades. */
@Service
public class CommercialService {

    private static final UUID NO_ID = new UUID(0L, 0L);

    private final OperatorCompanyRepository companies;
    private final CondominiumRepository condominiums;
    private final BlockRepository blocks;
    private final UnitRepository units;
    private final AccessService access;
    private final AuditService audit;

    public CommercialService(OperatorCompanyRepository companies, CondominiumRepository condominiums,
                             BlockRepository blocks, UnitRepository units, AccessService access,
                             AuditService audit) {
        this.companies = companies;
        this.condominiums = condominiums;
        this.blocks = blocks;
        this.units = units;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public OperatorCompany createCompany(String name, String taxId) {
        Actor actor = access.requirePlatformAdmin("company.create");
        if (companies.existsByTaxId(taxId)) {
            throw new ConflictException("A company with this tax id already exists");
        }
        OperatorCompany company = companies.save(new OperatorCompany(name, taxId));
        audit.record(actor.userId(), company.getId(), null, "company.create", "company",
                company.getId().toString(), AuditOutcome.SUCCESS);
        return company;
    }

    @Transactional(readOnly = true)
    public Page<OperatorCompany> listCompanies(Pageable pageable) {
        access.requirePlatformAdmin("company.list");
        return companies.findAll(pageable);
    }

    @Transactional
    public Condominium createCondominium(UUID companyId, String name, String taxId) {
        Actor actor = access.currentActor();
        boolean allowed = access.companyIdsAdministeredBy(actor).contains(companyId)
                || access.isPlatformAdmin(actor);
        if (!allowed) {
            audit.record(actor.userId(), companyId, null, "condominium.create", "company",
                    companyId.toString(), AuditOutcome.DENIED);
            throw new ForbiddenException("Not allowed to create condominiums for this company");
        }
        if (!companies.existsById(companyId)) {
            throw new NotFoundException("Company not found");
        }
        if (condominiums.existsByOperatorCompanyIdAndName(companyId, name)) {
            throw new ConflictException("A condominium with this name already exists for the company");
        }
        Condominium condominium = condominiums.save(new Condominium(companyId, name, taxId));
        audit.record(actor.userId(), companyId, condominium.getId(), "condominium.create", "condominium",
                condominium.getId().toString(), AuditOutcome.SUCCESS);
        return condominium;
    }

    @Transactional(readOnly = true)
    public Page<Condominium> listCondominiums(Pageable pageable) {
        Actor actor = access.currentActor();
        Set<UUID> condominiumIds = new HashSet<>(access.directCondominiumIds(actor));
        Set<UUID> companyIds = new HashSet<>(access.companyIdsAdministeredBy(actor));
        condominiumIds.add(NO_ID);
        companyIds.add(NO_ID);
        return condominiums.findVisible(condominiumIds, companyIds, pageable);
    }

    @Transactional(readOnly = true)
    public Condominium getCondominium(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.READ, "condominium.read");
        return condominiums.findById(condominiumId)
                .orElseThrow(() -> new NotFoundException("Condominium not found"));
    }

    @Transactional
    public Block createBlock(UUID condominiumId, String name) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "block.create");
        if (blocks.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ConflictException("Block already exists");
        }
        Block block = blocks.save(new Block(condominiumId, name));
        audit.record(actor.userId(), null, condominiumId, "block.create", "block",
                block.getId().toString(), AuditOutcome.SUCCESS);
        return block;
    }

    @Transactional(readOnly = true)
    public Page<Block> listBlocks(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "block.list");
        return blocks.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional
    public Unit createUnit(UUID condominiumId, UUID blockId, String number) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "unit.create");
        blocks.findByIdAndCondominiumId(blockId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Block not found"));
        if (units.existsByBlockIdAndNumber(blockId, number)) {
            throw new ConflictException("Unit already exists in this block");
        }
        Unit unit = units.save(new Unit(condominiumId, blockId, number));
        audit.record(actor.userId(), null, condominiumId, "unit.create", "unit",
                unit.getId().toString(), AuditOutcome.SUCCESS);
        return unit;
    }

    @Transactional(readOnly = true)
    public Page<Unit> listUnits(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "unit.list");
        return units.findByCondominiumId(condominiumId, pageable);
    }

    /** Uso interno (dados do próprio usuário); não aplica política de acesso. */
    @Transactional(readOnly = true)
    public Condominium findCondominiumInternal(UUID condominiumId) {
        return condominiums.findById(condominiumId).orElseThrow(() -> new NotFoundException("Condominium not found"));
    }

    @Transactional(readOnly = true)
    public Unit requireUnit(UUID condominiumId, UUID unitId) {
        return units.findByIdAndCondominiumId(unitId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Unit not found"));
    }
}
