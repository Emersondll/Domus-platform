package br.com.domus.platform.parcels;

import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

/** API de encomendas. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class ParcelController {

    private final ParcelService service;

    public ParcelController(ParcelService service) {
        this.service = service;
    }

    /**
     * @param unitId      unidade destinatária
     * @param carrier     transportadora, opcional
     * @param description descrição curta, opcional
     */
    public record RegisterParcelRequest(@NotNull UUID unitId, @Size(max = 80) String carrier,
                                        @Size(max = 200) String description) {
    }

    /** @param pickedUpByName nome de quem retirou */
    public record PickUpRequest(@NotBlank @Size(max = 160) String pickedUpByName) {
    }

    /**
     * @param id             identificador da encomenda
     * @param unitId         unidade destinatária
     * @param carrier        transportadora
     * @param description    descrição
     * @param status         RECEIVED ou PICKED_UP
     * @param receivedAt     instante do recebimento
     * @param pickedUpAt     instante da retirada
     * @param pickedUpByName quem retirou
     */
    public record ParcelResponse(UUID id, UUID unitId, String carrier, String description, String status,
                                 Instant receivedAt, Instant pickedUpAt, String pickedUpByName) {
        static ParcelResponse from(Parcel p) {
            return new ParcelResponse(p.getId(), p.getUnitId(), p.getCarrier(), p.getDescription(), p.getStatus(),
                    p.getReceivedAt(), p.getPickedUpAt(), p.getPickedUpByName());
        }
    }

    @PostMapping("/parcels")
    @ResponseStatus(HttpStatus.CREATED)
    public ParcelResponse register(@PathVariable UUID condominiumId, @Valid @RequestBody RegisterParcelRequest r) {
        return ParcelResponse.from(service.register(condominiumId, r.unitId(), r.carrier(), r.description()));
    }

    @PostMapping("/parcels/{parcelId}/pickup")
    public ParcelResponse pickUp(@PathVariable UUID condominiumId, @PathVariable UUID parcelId,
                                 @Valid @RequestBody PickUpRequest r) {
        return ParcelResponse.from(service.pickUp(condominiumId, parcelId, r.pickedUpByName()));
    }

    @GetMapping("/parcels")
    public PageResponse<ParcelResponse> list(@PathVariable UUID condominiumId,
                                             @PageableDefault(size = 50, sort = "receivedAt") Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, pageable), ParcelResponse::from);
    }

    @GetMapping("/me/parcels")
    public PageResponse<ParcelResponse> listMine(@PathVariable UUID condominiumId,
                                                 @PageableDefault(size = 50, sort = "receivedAt") Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), ParcelResponse::from);
    }
}
