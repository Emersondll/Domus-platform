package br.com.portaria.platform.reservations;

import br.com.portaria.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

/** API de áreas comuns e reservas. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * @param name     nome da área
     * @param capacity capacidade máxima de pessoas
     */
    public record CreateAreaRequest(@NotBlank @Size(max = 80) String name, @Min(1) int capacity) {
    }

    /**
     * @param id       identificador da área
     * @param name     nome da área
     * @param capacity capacidade máxima
     */
    public record AreaResponse(UUID id, String name, int capacity) {
        static AreaResponse from(CommonArea a) {
            return new AreaResponse(a.getId(), a.getName(), a.getCapacity());
        }
    }

    /**
     * @param areaId  área desejada
     * @param startAt início (futuro)
     * @param endAt   fim (posterior ao início, no máximo 12 horas)
     */
    public record ReserveRequest(@NotNull UUID areaId, @NotNull Instant startAt, @NotNull Instant endAt) {
    }

    /**
     * @param id      identificador da reserva
     * @param areaId  área reservada
     * @param unitId  unidade solicitante
     * @param startAt início
     * @param endAt   fim
     * @param status  CONFIRMED ou CANCELLED
     */
    public record ReservationResponse(UUID id, UUID areaId, UUID unitId, Instant startAt, Instant endAt,
                                      String status) {
        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.getId(), r.getAreaId(), r.getUnitId(), r.getStartAt(), r.getEndAt(),
                    r.getStatus());
        }
    }

    @PostMapping("/common-areas")
    @ResponseStatus(HttpStatus.CREATED)
    public AreaResponse createArea(@PathVariable UUID condominiumId, @Valid @RequestBody CreateAreaRequest r) {
        return AreaResponse.from(service.createArea(condominiumId, r.name(), r.capacity()));
    }

    @GetMapping("/common-areas")
    public PageResponse<AreaResponse> listAreas(@PathVariable UUID condominiumId,
                                                @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listAreas(condominiumId, pageable), AreaResponse::from);
    }

    @GetMapping("/me/common-areas")
    public PageResponse<AreaResponse> listAreasMine(@PathVariable UUID condominiumId,
                                                    @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return PageResponse.of(service.listAreasAsResident(condominiumId, pageable), AreaResponse::from);
    }

    @GetMapping("/reservations")
    public PageResponse<ReservationResponse> list(@PathVariable UUID condominiumId,
                                                  @PageableDefault(size = 50, sort = "startAt") Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, pageable), ReservationResponse::from);
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID condominiumId, @PathVariable UUID reservationId) {
        return ReservationResponse.from(service.cancelAsStaff(condominiumId, reservationId));
    }

    @GetMapping("/me/reservations")
    public PageResponse<ReservationResponse> listMine(@PathVariable UUID condominiumId,
                                                      @PageableDefault(size = 50, sort = "startAt") Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), ReservationResponse::from);
    }

    @PostMapping("/me/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@PathVariable UUID condominiumId, @Valid @RequestBody ReserveRequest r) {
        return ReservationResponse.from(service.reserve(condominiumId, r.areaId(), r.startAt(), r.endAt()));
    }

    @PostMapping("/me/reservations/{reservationId}/cancel")
    public ReservationResponse cancelMine(@PathVariable UUID condominiumId, @PathVariable UUID reservationId) {
        return ReservationResponse.from(service.cancelAsResident(condominiumId, reservationId));
    }
}
