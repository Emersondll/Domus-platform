package br.com.domus.platform.residents.controller;

import br.com.domus.platform.residents.entity.Resident;
import br.com.domus.platform.residents.entity.ResidentType;
import br.com.domus.platform.residents.service.HouseholdService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API da família da unidade, para o próprio morador. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/me/household")
public class HouseholdController {

    private final HouseholdService service;

    /** Construtor de HouseholdController; recebe as dependências por injeção. */
    public HouseholdController(HouseholdService service) {
        this.service = service;
    }

    /**
     * @param fullName     nome completo
     * @param relationship SPOUSE (cônjuge) ou DEPENDENT (filho ou outro familiar)
     * @param phone        telefone de contato, opcional
     */
    public record AddMemberRequest(@NotBlank @Size(max = 160) String fullName, @NotNull ResidentType relationship,
                                   @Size(max = 30) String phone) {
    }

    /**
     * @param id           identificador do cadastro
     * @param fullName     nome completo
     * @param relationship OWNER, TENANT, SPOUSE ou DEPENDENT
     * @param phone        telefone de contato
     * @param removable    se o próprio morador pode remover este cadastro (cônjuge e dependentes, nunca ele mesmo)
     */
    public record MemberResponse(UUID id, String fullName, ResidentType relationship, String phone, boolean removable) {
        static MemberResponse from(Resident r, UUID selfId) {
            boolean removable = (r.getResidentType() == ResidentType.SPOUSE || r.getResidentType() == ResidentType.DEPENDENT)
                    && !r.getId().equals(selfId);
            return new MemberResponse(r.getId(), r.getFullName(), r.getResidentType(), r.getPhone(), removable);
        }
    }

    @GetMapping
    /**
     * Atende `GET /condominiums/{condominiumId}/me/household`.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    public List<MemberResponse> list(@PathVariable UUID condominiumId) {
        UUID self = service.selfId(condominiumId);
        return service.list(condominiumId).stream().map(r -> MemberResponse.from(r, self)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/me/household`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public MemberResponse add(@PathVariable UUID condominiumId, @Valid @RequestBody AddMemberRequest r) {
        return MemberResponse.from(service.add(condominiumId, r.fullName(), r.relationship(), r.phone()),
                service.selfId(condominiumId));
    }

    @PostMapping("/{residentId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    /**
     * Atende `POST /condominiums/{condominiumId}/me/household/{residentId}/remove`.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     */
    public void remove(@PathVariable UUID condominiumId, @PathVariable UUID residentId) {
        service.remove(condominiumId, residentId);
    }
}
