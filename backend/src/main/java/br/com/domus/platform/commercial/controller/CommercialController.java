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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de gestão comercial. Sem regra de negócio: delega ao {@link CommercialService}. */
@RestController
@RequestMapping("/api/v1")
public class CommercialController {

    private final CommercialService service;

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
     */
    public record CompanyResponse(UUID id, String name, String taxId) {
        static CompanyResponse from(OperatorCompany c) {
            return new CompanyResponse(c.getId(), c.getName(), c.getTaxId());
        }
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
     * @param companies     empresas operadoras que o usuário administra (todas, para o administrador da plataforma)
     */
    public record AdminScopeResponse(boolean platformAdmin, List<CompanyResponse> companies) {
    }

    /** O que o usuário pode administrar; a tela usa para mostrar ou esconder a área de administração. */
    @GetMapping("/me/admin-scope")
    public AdminScopeResponse adminScope() {
        CommercialService.AdminScope scope = service.adminScope();
        return new AdminScopeResponse(scope.platformAdmin(), scope.companies().stream().map(CompanyResponse::from).toList());
    }

    @PostMapping("/companies")
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse createCompany(@Valid @RequestBody CreateCompanyRequest request) {
        return CompanyResponse.from(service.createCompany(request.name(), request.taxId()));
    }

    @GetMapping("/companies")
    public PageResponse<CompanyResponse> listCompanies(@PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listCompanies(pageable), CompanyResponse::from);
    }

    @PostMapping("/condominiums")
    @ResponseStatus(HttpStatus.CREATED)
    public CondominiumResponse createCondominium(@Valid @RequestBody CreateCondominiumRequest request) {
        return CondominiumResponse.from(
                service.createCondominium(request.operatorCompanyId(), request.name(), request.taxId()));
    }

    @GetMapping("/condominiums")
    public PageResponse<CondominiumResponse> listCondominiums(
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listCondominiums(pageable), CondominiumResponse::from);
    }

    @GetMapping("/condominiums/{condominiumId}")
    public CondominiumResponse getCondominium(@PathVariable UUID condominiumId) {
        return CondominiumResponse.from(service.getCondominium(condominiumId));
    }

    @PostMapping("/condominiums/{condominiumId}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public BlockResponse createBlock(@PathVariable UUID condominiumId, @Valid @RequestBody CreateBlockRequest request) {
        return BlockResponse.from(service.createBlock(condominiumId, request.name()));
    }

    @GetMapping("/condominiums/{condominiumId}/blocks")
    public PageResponse<BlockResponse> listBlocks(@PathVariable UUID condominiumId,
                                                  @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listBlocks(condominiumId, pageable), BlockResponse::from);
    }

    @PostMapping("/condominiums/{condominiumId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse createUnit(@PathVariable UUID condominiumId, @Valid @RequestBody CreateUnitRequest request) {
        return UnitResponse.from(service.createUnit(condominiumId, request.blockId(), request.number()));
    }

    @GetMapping("/condominiums/{condominiumId}/units")
    public PageResponse<UnitResponse> listUnits(@PathVariable UUID condominiumId,
                                                @PageableDefault(size = 50, sort = "number") Pageable pageable) {
        return PageResponse.of(service.listUnits(condominiumId, pageable), UnitResponse::from);
    }
}
