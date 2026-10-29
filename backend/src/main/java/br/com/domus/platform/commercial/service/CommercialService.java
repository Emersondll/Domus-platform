package br.com.domus.platform.commercial.service;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.repository.BlockRepository;
import br.com.domus.platform.commercial.repository.CondominiumRepository;
import br.com.domus.platform.commercial.repository.OperatorCompanyRepository;
import br.com.domus.platform.commercial.repository.UnitRepository;
import br.com.domus.platform.shared.ViewSessionRevoker;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.ForbiddenException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final ViewSessionRevoker viewSessions;

    /** Construtor de CommercialService; recebe as dependências por injeção. */
    public CommercialService(OperatorCompanyRepository companies, CondominiumRepository condominiums,
                             BlockRepository blocks, UnitRepository units, AccessService access,
                             AuditService audit, ViewSessionRevoker viewSessions) {
        this.companies = companies;
        this.condominiums = condominiums;
        this.blocks = blocks;
        this.units = units;
        this.access = access;
        this.audit = audit;
        this.viewSessions = viewSessions;
    }

    /**
     * Cadastra uma empresa operadora.
     *
     * @param name nome
     * @param taxId CNPJ
     */
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

    /**
     * Lista as empresas operadoras visíveis ao usuário.
     *
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<OperatorCompany> listCompanies(Pageable pageable) {
        access.requirePlatformAdmin("company.list");
        return companies.findAll(pageable);
    }

    /**
     * Empresas que o chamador administra (todas, para o administrador da plataforma). Não audita e não lança exceção:
     * quem não administra nada recebe lista vazia; a tela usa isso só para decidir o que mostrar, o servidor continua
     * recusando cada ação.
     */
    @Transactional(readOnly = true)
    public AdminScope adminScope() {
        Actor actor = access.currentActor();
        boolean platform = access.isPlatformAdmin(actor);
        List<OperatorCompany> mine = platform
                ? companies.findAll(Sort.by("name"))
                : companies.findAllById(access.companyIdsAdministeredBy(actor)).stream()
                        .sorted(Comparator.comparing(OperatorCompany::getName)).toList();
        return new AdminScope(platform, !access.operatorCondominiumIds(actor).isEmpty(), mine);
    }

    /**
     * @param platformAdmin administra a plataforma
     * @param operator      atua como operador da central em algum condomínio
     * @param companies     empresas administradas pelo chamador
     */
    public record AdminScope(boolean platformAdmin, boolean operator, List<OperatorCompany> companies) {
    }

    /**
     * Cria um condomínio vinculado à empresa operadora.
     *
     * @param companyId empresa operadora
     * @param name nome
     * @param taxId CNPJ
     */
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

    /**
     * Lista os condomínios visíveis ao usuário.
     *
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<Condominium> listCondominiums(Pageable pageable) {
        Actor actor = access.currentActor();
        if (access.isPlatformAdmin(actor)) {
            // dado comercial (nome e situação), sem dado de morador: a plataforma precisa ver todos para suspender e reativar
            return condominiums.findAll(pageable);
        }
        Set<UUID> condominiumIds = new HashSet<>(access.directCondominiumIds(actor));
        Set<UUID> companyIds = new HashSet<>(access.companyIdsAdministeredBy(actor));
        condominiumIds.add(NO_ID);
        companyIds.add(NO_ID);
        return condominiums.findVisible(condominiumIds, companyIds, pageable);
    }

    /**
     * Consulta um condomínio dentro do escopo do usuário.
     *
     * @param condominiumId condomínio
     */
    @Transactional(readOnly = true)
    public Condominium getCondominium(UUID condominiumId) {
        Condominium condominium = condominiums.findById(condominiumId)
                .orElseThrow(() -> new NotFoundException("Condominium not found"));
        // quem administra a empresa (ou a plataforma) consulta o condomínio mesmo suspenso, para poder reativá-lo
        Actor actor = access.currentActor();
        if (!access.isPlatformAdmin(actor)
                && !access.companyIdsAdministeredBy(actor).contains(condominium.getOperatorCompanyId())) {
            access.require(condominiumId, AccessLevel.READ, "condominium.read");
        }
        return condominium;
    }

    /**
     * Suspende ou reativa uma empresa operadora (só o administrador da plataforma). Suspensa, nenhum administrador, síndico,
     * operador ou morador dela usa o app, e agentes e equipamentos dos condomínios dela são recusados. Nada é apagado.
     *
     * @param companyId empresa
     * @param suspended verdadeiro para suspender, falso para reativar
     */
    @Transactional
    public OperatorCompany setCompanySuspended(UUID companyId, boolean suspended) {
        String action = suspended ? "company.suspend" : "company.reactivate";
        Actor actor = access.requirePlatformAdmin(action);
        OperatorCompany company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        company.setSuspended(suspended);
        if (suspended) {
            condominiums.findByOperatorCompanyId(companyId)
                    .forEach(c -> viewSessions.revokeForCondominium(c.getId(), "COMPANY_SUSPENDED"));
        }
        audit.record(actor.userId(), companyId, null, action, "company", companyId.toString(), AuditOutcome.SUCCESS);
        return company;
    }

    /**
     * Suspende ou reativa um condomínio (administrador da empresa dona ou da plataforma). Mesmo efeito da suspensão da
     * empresa, restrito a este condomínio.
     *
     * @param condominiumId condomínio
     * @param suspended verdadeiro para suspender, falso para reativar
     */
    @Transactional
    public Condominium setCondominiumSuspended(UUID condominiumId, boolean suspended) {
        String action = suspended ? "condominium.suspend" : "condominium.reactivate";
        Actor actor = access.currentActor();
        Condominium condominium = condominiums.findById(condominiumId).orElse(null);
        boolean allowed = condominium != null && (access.isPlatformAdmin(actor)
                || access.companyIdsAdministeredBy(actor).contains(condominium.getOperatorCompanyId()));
        if (!allowed) {
            audit.record(actor.userId(), null, condominiumId, action, "condominium", condominiumId.toString(),
                    AuditOutcome.DENIED);
            throw new NotFoundException("Condominium not found");
        }
        condominium.setSuspended(suspended);
        if (suspended) {
            viewSessions.revokeForCondominium(condominiumId, "CONDOMINIUM_SUSPENDED");
        }
        audit.record(actor.userId(), condominium.getOperatorCompanyId(), condominiumId, action, "condominium",
                condominiumId.toString(), AuditOutcome.SUCCESS);
        return condominium;
    }

    /**
     * Cria um bloco no condomínio.
     *
     * @param condominiumId condomínio
     * @param name nome
     */
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

    /**
     * Lista os blocos do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<Block> listBlocks(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "block.list");
        return blocks.findByCondominiumId(condominiumId, pageable);
    }

    /**
     * Cria uma unidade em um bloco do condomínio.
     *
     * @param condominiumId condomínio
     * @param blockId bloco
     * @param number número
     */
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

    /**
     * Lista as unidades do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
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

    /** "Bloco / número" da unidade, para telas operacionais; uso interno, sem política de acesso. */
    @Transactional(readOnly = true)
    public String unitLabel(UUID condominiumId, UUID unitId) {
        if (unitId == null) {
            return null;
        }
        return units.findByIdAndCondominiumId(unitId, condominiumId)
                .map(u -> blocks.findByIdAndCondominiumId(u.getBlockId(), condominiumId).map(Block::getName).orElse("?")
                        + " / " + u.getNumber())
                .orElse(null);
    }

    /**
     * Devolve a unidade ou falha com 404 se ela não pertence ao condomínio.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     */
    @Transactional(readOnly = true)
    public Unit requireUnit(UUID condominiumId, UUID unitId) {
        return units.findByIdAndCondominiumId(unitId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Unit not found"));
    }
}
