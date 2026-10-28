package br.com.domus.platform.vehicles.controller;

import br.com.domus.platform.vehicles.entity.Vehicle;
import br.com.domus.platform.vehicles.service.VehicleService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de veículos: listagem da portaria e autoatendimento do morador. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class VehicleController {

    private final VehicleService service;

    /** Construtor de VehicleController; recebe as dependências por injeção. */
    public VehicleController(VehicleService service) {
        this.service = service;
    }

    /**
     * @param plate placa (letras e números; hífen é ignorado)
     * @param model modelo, opcional
     * @param color cor, opcional
     */
    public record RegisterVehicleRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{5,10}") String plate,
                                         @Size(max = 60) String model, @Size(max = 30) String color) {
    }

    /**
     * @param id     identificador do veículo
     * @param unitId unidade proprietária
     * @param plate  placa normalizada
     * @param model  modelo
     * @param color  cor
     */
    public record VehicleResponse(UUID id, UUID unitId, String plate, String model, String color) {
        static VehicleResponse from(Vehicle v) {
            return new VehicleResponse(v.getId(), v.getUnitId(), v.getPlate(), v.getModel(), v.getColor());
        }
    }

    @GetMapping("/vehicles")
    /**
     * Atende `GET /condominiums/{condominiumId}/vehicles`.
     *
     * @param condominiumId condomínio
     * @param plate placa
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<VehicleResponse> list(@PathVariable UUID condominiumId, @RequestParam(required = false) String plate,
                                              @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, plate, pageable), VehicleResponse::from);
    }

    @GetMapping("/me/vehicles")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/vehicles`.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página no formato da API
     */
    public PageResponse<VehicleResponse> listMine(@PathVariable UUID condominiumId,
                                                  @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), VehicleResponse::from);
    }

    @PostMapping("/me/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    /**
     * Atende `POST /condominiums/{condominiumId}/me/vehicles`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public VehicleResponse registerMine(@PathVariable UUID condominiumId,
                                        @Valid @RequestBody RegisterVehicleRequest r) {
        return VehicleResponse.from(service.registerMine(condominiumId, r.plate(), r.model(), r.color()));
    }
}
