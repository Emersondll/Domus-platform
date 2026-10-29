package br.com.domus.platform.residents.controller;

import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.residents.entity.Pet;
import br.com.domus.platform.residents.service.PetService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de animais de estimação: administração (por unidade) e o próprio morador (animais da sua unidade). */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class PetController {

    private final PetService service;

    /** Construtor de PetController; recebe as dependências por injeção. */
    public PetController(PetService service) {
        this.service = service;
    }

    /**
     * @param unitId  unidade do animal (usado só pela administração; o morador usa a própria unidade)
     * @param name    nome do animal
     * @param species DOG, CAT, BIRD ou OTHER
     * @param breed   raça, opcional
     * @param notes   observação para a portaria (ex.: "não sai sem guia"), opcional
     */
    public record PetRequest(UUID unitId, @NotBlank @Size(max = 60) String name,
                             @NotNull @Pattern(regexp = "DOG|CAT|BIRD|OTHER") String species,
                             @Size(max = 60) String breed, @Size(max = 200) String notes) {
    }

    /**
     * @param id      identificador
     * @param unitId  unidade do animal
     * @param name    nome
     * @param species DOG, CAT, BIRD ou OTHER
     * @param breed   raça
     * @param notes   observação
     */
    public record PetResponse(UUID id, UUID unitId, String name, String species, String breed, String notes) {
        static PetResponse from(Pet p) {
            return new PetResponse(p.getId(), p.getUnitId(), p.getName(), p.getSpecies(), p.getBreed(), p.getNotes());
        }
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/pets`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/pets")
    @ResponseStatus(HttpStatus.CREATED)
    public PetResponse register(@PathVariable UUID condominiumId, @Valid @RequestBody PetRequest r) {
        if (r.unitId() == null) {
            throw new InvalidRequestException("unitId is required");
        }
        return PetResponse.from(service.register(condominiumId, r.unitId(), r.name(), r.species(), r.breed(),
                r.notes()));
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/pets`.
     *
     * @param condominiumId condomínio
     * @param unitId unidade
     * @param pageable paginação
     * @return página no formato da API
     */
    @GetMapping("/pets")
    public PageResponse<PetResponse> list(@PathVariable UUID condominiumId,
                                          @RequestParam(required = false) UUID unitId,
                                          @PageableDefault(size = 100, sort = "name") Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, unitId, pageable), PetResponse::from);
    }

    /**
     * Atende `PUT /condominiums/{condominiumId}/pets/{petId}`.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     * @param r corpo da requisição (validado)
     */
    @PutMapping("/pets/{petId}")
    public PetResponse update(@PathVariable UUID condominiumId, @PathVariable UUID petId,
                              @Valid @RequestBody PetRequest r) {
        return PetResponse.from(service.update(condominiumId, petId, r.name(), r.species(), r.breed(), r.notes()));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/pets/{petId}/remove`.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     */
    @PostMapping("/pets/{petId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID condominiumId, @PathVariable UUID petId) {
        service.remove(condominiumId, petId);
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/me/pets`.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    @GetMapping("/me/pets")
    public List<PetResponse> listMine(@PathVariable UUID condominiumId) {
        return service.listMine(condominiumId).stream().map(PetResponse::from).toList();
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/me/pets`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/me/pets")
    @ResponseStatus(HttpStatus.CREATED)
    public PetResponse registerMine(@PathVariable UUID condominiumId, @Valid @RequestBody PetRequest r) {
        return PetResponse.from(service.registerMine(condominiumId, r.name(), r.species(), r.breed(), r.notes()));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/me/pets/{petId}/remove`.
     *
     * @param condominiumId condomínio
     * @param petId pet id
     */
    @PostMapping("/me/pets/{petId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMine(@PathVariable UUID condominiumId, @PathVariable UUID petId) {
        service.removeMine(condominiumId, petId);
    }
}
