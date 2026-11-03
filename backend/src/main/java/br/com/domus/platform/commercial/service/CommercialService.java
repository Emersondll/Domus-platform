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
import br.com.domus.platform.shared.error.InvalidRequestException;
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
    private final boolean requireValidCnpj;

    /** Construtor de CommercialService; recebe as dependências por injeção. */
    public CommercialService(OperatorCompanyRepository companies, CondominiumRepository condominiums,
                             BlockRepository blocks, UnitRepository units, AccessService access,
                             AuditService audit, ViewSessionRevoker viewSessions,
                             @org.springframework.beans.factory.annotation.Value("${portaria.commercial.require-valid-cnpj:false}") boolean requireValidCnpj) {
        this.requireValidCnpj = requireValidCnpj;
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
    public OperatorCompany createCompany(String name, String rawTaxId) {
        Actor actor = access.requirePlatformAdmin("company.create");
        String taxId = normalizeTaxId(rawTaxId);
        if (companies.existsByTaxId(taxId)) {
            throw new ConflictException("A company with this tax id already exists");
        }
        OperatorCompany company = companies.save(new OperatorCompany(name, taxId));
        audit.record(actor.userId(), company.getId(), null, "company.create", "company",
                company.getId().toString(), AuditOutcome.SUCCESS);
        return company;
    }

    /**
     * Corrige o nome e o CNPJ de uma empresa operadora. Só a plataforma: trocar o CNPJ do contratante é decisão contratual. A
     * auditoria guarda o valor antigo e o novo.
     *
     * @param companyId empresa
     * @param name nome corrigido
     * @param taxId CNPJ corrigido
     */
    @Transactional
    public OperatorCompany updateCompany(UUID companyId, String name, String rawTaxId) {
        String taxId = normalizeTaxId(rawTaxId);
        Actor actor = access.requirePlatformAdmin("company.update");
        OperatorCompany company = companies.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));
        if (companies.existsByTaxIdAndIdNot(taxId, companyId)) {
            throw new ConflictException("A company with this tax id already exists");
        }
        String detail = changes(company.getName(), name, company.getTaxId(), taxId);
        company.correct(name, taxId);
        audit.record(actor.userId(), companyId, null, "company.update", "company", companyId.toString(),
                AuditOutcome.SUCCESS, detail);
        return company;
    }

    /**
     * Corrige o nome e (se informado) o CNPJ de um condomínio. Plataforma ou administrador da empresa dona; qualquer outro
     * recebe 404. CNPJ em branco mantém o atual. A auditoria guarda o valor antigo e o novo.
     *
     * @param condominiumId condomínio
     * @param name nome corrigido
     * @param taxId CNPJ corrigido, ou nulo para manter
     */
    @Transactional
    public Condominium updateCondominium(UUID condominiumId, String name, String rawTaxId) {
        Actor actor = access.currentActor();
        Condominium condominium = condominiums.findById(condominiumId).orElse(null);
        boolean allowed = condominium != null && (access.isPlatformAdmin(actor)
                || access.companyIdsAdministeredBy(actor).contains(condominium.getOperatorCompanyId()));
        if (!allowed) {
            audit.record(actor.userId(), null, condominiumId, "condominium.update", "condominium", condominiumId.toString(),
                    AuditOutcome.DENIED);
            throw new NotFoundException("Condominium not found");
        }
        if (condominiums.existsByOperatorCompanyIdAndNameAndIdNot(condominium.getOperatorCompanyId(), name, condominiumId)) {
            throw new ConflictException("A condominium with this name already exists for the company");
        }
        String newTaxId = rawTaxId == null || rawTaxId.isBlank() ? condominium.getTaxId() : normalizeTaxId(rawTaxId);
        String detail = changes(condominium.getName(), name, condominium.getTaxId(), newTaxId);
        condominium.correct(name, newTaxId);
        audit.record(actor.userId(), condominium.getOperatorCompanyId(), condominiumId, "condominium.update", "condominium",
                condominiumId.toString(), AuditOutcome.SUCCESS, detail);
        return condominium;
    }

    /** Texto curto com o antes e o depois do que mudou (só nome e CNPJ de pessoa jurídica, nunca dado de morador). */
    private static String changes(String oldName, String newName, String oldTaxId, String newTaxId) {
        StringBuilder sb = new StringBuilder();
        if (!java.util.Objects.equals(oldName, newName)) {
            sb.append("name: '").append(oldName).append("' -> '").append(newName).append("'");
        }
        if (!java.util.Objects.equals(oldTaxId, newTaxId)) {
            sb.append(sb.length() == 0 ? "" : "; ").append("taxId: '").append(oldTaxId).append("' -> '").append(newTaxId).append("'");
        }
        return sb.length() == 0 ? "no change" : sb.toString();
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
    public Condominium createCondominium(UUID companyId, String name, String rawTaxId) {
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
        Condominium condominium = condominiums.save(new Condominium(companyId, name,
                rawTaxId == null || rawTaxId.isBlank() ? rawTaxId : normalizeTaxId(rawTaxId)));
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

    /** Corrige o nome de um bloco (digitado errado, mudou de nome). */
    @Transactional
    public Block renameBlock(UUID condominiumId, UUID blockId, String name) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "block.update");
        Block block = blocks.findByIdAndCondominiumId(blockId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Block not found"));
        if (!block.getName().equals(name) && blocks.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ConflictException("Block already exists");
        }
        block.rename(name);
        audit.record(actor.userId(), null, condominiumId, "block.update", "block", blockId.toString(), AuditOutcome.SUCCESS);
        return block;
    }

    /** Corrige o número de uma unidade (a unidade, os moradores e o histórico dela continuam os mesmos). */
    @Transactional
    public Unit renumberUnit(UUID condominiumId, UUID unitId, String number) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "unit.update");
        Unit unit = units.findByIdAndCondominiumId(unitId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Unit not found"));
        if (!unit.getNumber().equals(number) && units.existsByBlockIdAndNumber(unit.getBlockId(), number)) {
            throw new ConflictException("Unit already exists in this block");
        }
        unit.renumber(number);
        audit.record(actor.userId(), null, condominiumId, "unit.update", "unit", unitId.toString(), AuditOutcome.SUCCESS);
        return unit;
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

    /**
     * Normaliza o CNPJ para só dígitos. Com 14 dígitos, os verificadores são sempre conferidos (pega erro de digitação). Fora disso,
     * com {@code portaria.commercial.require-valid-cnpj} ligado (produção) o valor é recusado; desligado (desenvolvimento e testes,
     * que usam identificadores fictícios), é aceito como veio.
     */
    private String normalizeTaxId(String value) {
        String digits = Cnpj.digitsOnly(value);
        if (digits.matches("\\d{14}")) {
            if (!Cnpj.isValid(digits)) {
                throw new InvalidRequestException("Invalid CNPJ: check digits do not match");
            }
            return digits;
        }
        if (requireValidCnpj) {
            throw new InvalidRequestException("Invalid CNPJ: must have 14 digits");
        }
        return value;
    }
}
