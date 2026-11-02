package br.com.domus.platform.commercial.controller;

import br.com.domus.platform.commercial.entity.Block;
import br.com.domus.platform.commercial.entity.Condominium;
import br.com.domus.platform.commercial.entity.OperatorCompany;
import br.com.domus.platform.commercial.entity.Unit;
import br.com.domus.platform.commercial.service.CommercialService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de gestão comercial. Sem regra de negócio: delega ao {@link CommercialService}. */
@RestController
@RequestMapping("/api/v1")
public class CommercialController {

    private final CommercialService service;

    /** Construtor de CommercialController; recebe as dependências por injeção. */
    public CommercialController(CommercialService service) {
        this.service = service;
    }

    /**
     * @param name  nome da empresa operadora
     * @param taxId CNPJ (somente dígitos)
     */
    public record CreateCompanyRequest(@NotBlank @Size(max = 160) String name,
                                       @NotBlank @Size(max = 20) String taxId) {
    }

    /**
     * @param id    identificador da empresa
     * @param name  nome da empresa
     * @param taxId CNPJ
     * @param status ACTIVE ou SUSPENDED (acesso desligado, registros mantidos)
     */
    public record CompanyResponse(UUID id, String name, String taxId, String status) {
        static CompanyResponse from(OperatorCompany c) {
            return new CompanyResponse(c.getId(), c.getName(), c.getTaxId(), c.getStatus());
        }
    }

    /**
     * @param name  nome corrigido da empresa operadora
     * @param taxId CNPJ corrigido (único entre as empresas)
     */
    public record UpdateCompanyRequest(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 20) String taxId) {
    }

    /**
     * @param name  nome corrigido do condomínio (único dentro da empresa)
     * @param taxId CNPJ corrigido; em branco ou ausente mantém o atual
     */
    public record UpdateCondominiumRequest(@NotBlank @Size(max = 160) String name, @Size(max = 20) String taxId) {
    }

    /**
     * @param operatorCompanyId empresa operadora responsável; validada contra as associações do chamador
     * @param name              nome do condomínio
     * @param taxId             CNPJ do condomínio, opcional
     */
    public record CreateCondominiumRequest(@NotNull UUID operatorCompanyId,
                                           @NotBlank @Size(max = 160) String name,
                                           @Size(max = 20) String taxId) {
    }

    /**
     * @param id                identificador do condomínio
     * @param operatorCompanyId empresa operadora responsável
     * @param name              nome do condomínio
     * @param status            situação contratual
     */
    public record CondominiumResponse(UUID id, UUID operatorCompanyId, String name, String status) {
        static CondominiumResponse from(Condominium c) {
            return new CondominiumResponse(c.getId(), c.getOperatorCompanyId(), c.getName(), c.getStatus());
        }
    }

    /** @param name nome do bloco ou torre */
    public record CreateBlockRequest(@NotBlank @Size(max = 60) String name) {
    }

    /**
     * @param id   identificador do bloco
     * @param name nome do bloco
     */
    public record BlockResponse(UUID id, String name) {
        static BlockResponse from(Block b) {
            return new BlockResponse(b.getId(), b.getName());
        }
    }

    /**
     * @param number novo número da unidade
     */
    public record RenumberUnitRequest(@NotBlank @Size(max = 20) String number) {
    }

    /**
     * @param blockId bloco ao qual a unidade pertence (deve ser do mesmo condomínio)
     * @param number  número ou identificador da unidade
     */
    public record CreateUnitRequest(@NotNull UUID blockId, @NotBlank @Size(max = 20) String number) {
    }

    /**
     * @param id      identificador da unidade
     * @param blockId bloco da unidade
     * @param number  número da unidade
     */
    public record UnitResponse(UUID id, UUID blockId, String number) {
        static UnitResponse from(Unit u) {
            return new UnitResponse(u.getId(), u.getBlockId(), u.getNumber());
        }
    }

    /**
     * @param platformAdmin administra a plataforma toda
     * @param operator      atua como operador da central em algum condomínio (a tela só consulta atendimentos e turno se for)
     * @param companies     empresas operadoras que o usuário administra (todas, para o administrador da plataforma)
     */
    public record AdminScopeResponse(boolean platformAdmin, boolean operator, List<CompanyResponse> companies) {
    }

    /** O que o usuário pode administrar; a tela usa para mostrar ou esconder a área de administração. */
    @GetMapping("/me/admin-scope")
    public AdminScopeResponse adminScope() {
        CommercialService.AdminScope scope = service.adminScope();
        return new AdminScopeResponse(scope.platformAdmin(), scope.operator(), scope.companies().stream().map(CompanyResponse::from).toList());
    }

    /**
     * Atende `POST /companies`.
     *
     * @param request corpo da requisição
     */
    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return CompanyResponse.from(service.createCompany(request.name(), request.taxId()));
    }

    /**
     * Atende `GET /companies`.
     *
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/companies")
    public PageResponse<CompanyResponse> listCompanies(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listCompanies(pageable), CompanyResponse::from);
    }

    /**
     * Atende `POST /companies/{companyId}/suspend` (só o administrador da plataforma).
     *
     * @param companyId empresa
     */
    @PostMapping("/companies/{companyId}/suspend")
    public CompanyResponse suspendCompany(@PathVariable UUID companyId) {
        return CompanyResponse.from(service.setCompanySuspended(companyId, true));
    }

    /**
     * Atende `POST /companies/{companyId}/reactivate` (só o administrador da plataforma).
     *
     * @param companyId empresa
     */
    @PostMapping("/companies/{companyId}/reactivate")
    public CompanyResponse reactivateCompany(@PathVariable UUID companyId) {
        return CompanyResponse.from(service.setCompanySuspended(companyId, false));
    }

    /** Atende `PUT /companies/{companyId}`: só a plataforma corrige nome e CNPJ da empresa. */
    @PutMapping("/companies/{companyId}")
    public CompanyResponse updateCompany(@PathVariable UUID companyId, @Valid @RequestBody UpdateCompanyRequest request) {
        return CompanyResponse.from(service.updateCompany(companyId, request.name().trim(), request.taxId().trim()));
    }

    /** Atende `PUT /condominiums/{condominiumId}`: plataforma ou administrador da empresa dona corrige nome e CNPJ. */
    @PutMapping("/condominiums/{condominiumId}")
    public CondominiumResponse updateCondominium(@PathVariable UUID condominiumId,
                                                 @Valid @RequestBody UpdateCondominiumRequest request) {
        return CondominiumResponse.from(service.updateCondominium(condominiumId, request.name().trim(),
                request.taxId() == null ? null : request.taxId().trim()));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/suspend` (administrador da empresa dona ou da plataforma).
     *
     * @param condominiumId condomínio
     */
    @PostMapping("/condominiums/{condominiumId}/suspend")
    public CondominiumResponse suspendCondominium(@PathVariable UUID condominiumId) {
        return CondominiumResponse.from(service.setCondominiumSuspended(condominiumId, true));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/reactivate` (administrador da empresa dona ou da plataforma).
     *
     * @param condominiumId condomínio
     */
    @PostMapping("/condominiums/{condominiumId}/reactivate")
    public CondominiumResponse reactivateCondominium(@PathVariable UUID condominiumId) {
        return CondominiumResponse.from(service.setCondominiumSuspended(condominiumId, false));
    }

    /**
     * Atende `POST /condominiums`.
     *
     * @param request corpo da requisição
     */
    @PostMapping("/condominiums")
    @ResponseStatus(HttpStatus.CREATED)
    public CondominiumResponse createCondominium(@Valid @RequestBody CreateCondominiumRequest request) {
        return CondominiumResponse.from(
                service.createCondominium(request.operatorCompanyId(), request.name(), request.taxId()));
    }

    /**
     * Atende `GET /condominiums`.
     *
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/condominiums")
    public PageResponse<CondominiumResponse> listCondominiums(
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listCondominiums(pageable), CondominiumResponse::from);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}`.
     *
     * @param condominiumId condomínio
     */
    @GetMapping("/condominiums/{condominiumId}")
    public CondominiumResponse getCondominium(@PathVariable UUID condominiumId) {
        return CondominiumResponse.from(service.getCondominium(condominiumId));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/blocks`.
     *
     * @param condominiumId condomínio
     * @param request corpo da requisição
     */
    @PostMapping("/condominiums/{condominiumId}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockResponse createBlock(@PathVariable UUID condominiumId, @Valid @RequestBody CreateBlockRequest request) {
        return BlockResponse.from(service.createBlock(condominiumId, request.name()));
    }

    /** Atende `PUT /condominiums/{condominiumId}/blocks/{blockId}`: corrige o nome do bloco. */
    @PutMapping("/condominiums/{condominiumId}/blocks/{blockId}")
    public BlockResponse renameBlock(@PathVariable UUID condominiumId, @PathVariable UUID blockId,
                                     @Valid @RequestBody CreateBlockRequest r) {
        Block b = service.renameBlock(condominiumId, blockId, r.name());
        return new BlockResponse(b.getId(), b.getName());
    }

    /** Atende `PUT /condominiums/{condominiumId}/units/{unitId}`: corrige o número da unidade. */
    @PutMapping("/condominiums/{condominiumId}/units/{unitId}")
    public UnitResponse renumberUnit(@PathVariable UUID condominiumId, @PathVariable UUID unitId,
                                     @Valid @RequestBody RenumberUnitRequest r) {
        Unit u = service.renumberUnit(condominiumId, unitId, r.number());
        return new UnitResponse(u.getId(), u.getBlockId(), u.getNumber());
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/blocks`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/condominiums/{condominiumId}/blocks")
    public PageResponse<BlockResponse> listBlocks(@PathVariable UUID condominiumId,
                                                  @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listBlocks(condominiumId, pageable), BlockResponse::from);
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/units`.
     *
     * @param condominiumId condomínio
     * @param request corpo da requisição
     */
    @PostMapping("/condominiums/{condominiumId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse createUnit(@PathVariable UUID condominiumId, @Valid @RequestBody CreateUnitRequest request) {
        return UnitResponse.from(service.createUnit(condominiumId, request.blockId(), request.number()));
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/units`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/condominiums/{condominiumId}/units")
    public PageResponse<UnitResponse> listUnits(@PathVariable UUID condominiumId,
                                                @PageableDefault(size = 50, sort = "number") Pageable pageable) {
        return PageResponse.of(service.listUnits(condominiumId, pageable), UnitResponse::from);
    }
}
