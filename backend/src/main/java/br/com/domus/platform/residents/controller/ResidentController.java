package br.com.domus.platform.residents.controller;

import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.service.ResidentService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** API de moradores, escopada por condomínio. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/residents")
public class ResidentController {

    private final ResidentService service;

    public ResidentController(ResidentService service) {
        this.service = service;
    }

    /**
     * @param unitId       unidade do morador (deve pertencer ao condomínio da URL)
     * @param fullName     nome completo
     * @param email        e-mail de contato, opcional
     * @param phone        telefone de contato, opcional
     * @param residentType vínculo com a unidade
     */
    public record CreateResidentRequest(@NotNull UUID unitId, @NotBlank @Size(max = 160) String fullName,
                                        @Email @Size(max = 190) String email, @Size(max = 30) String phone,
                                        @NotNull ResidentType residentType) {
    }

    /**
     * @param id           identificador do morador
     * @param unitId       unidade do morador
     * @param fullName     nome completo
     * @param email        e-mail de contato
     * @param phone        telefone de contato
     * @param residentType vínculo com a unidade
     * @param active       indica se o cadastro está ativo
     */
    public record ResidentResponse(UUID id, UUID unitId, String fullName, String email, String phone,
                                   ResidentType residentType, boolean active) {
        static ResidentResponse from(Resident r) {
            return new ResidentResponse(r.getId(), r.getUnitId(), r.getFullName(), r.getEmail(), r.getPhone(),
                    r.getResidentType(), r.isActive());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResidentResponse create(@PathVariable UUID condominiumId, @Valid @RequestBody CreateResidentRequest r) {
        return ResidentResponse.from(service.create(condominiumId, r.unitId(), r.fullName(), r.email(),
                r.phone(), r.residentType()));
    }

    @GetMapping
    public PageResponse<ResidentResponse> list(@PathVariable UUID condominiumId,
                                               @PageableDefault(size = 50, sort = "fullName") Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, pageable), ResidentResponse::from);
    }

    @GetMapping("/{residentId}")
    public ResidentResponse get(@PathVariable UUID condominiumId, @PathVariable UUID residentId) {
        return ResidentResponse.from(service.get(condominiumId, residentId));
    }
}
