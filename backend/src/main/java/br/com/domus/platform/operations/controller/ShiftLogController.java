package br.com.domus.platform.operations.controller;

import br.com.domus.platform.operations.entity.ShiftLogEntry;
import br.com.domus.platform.operations.service.ShiftLogService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API do livro de turno. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/shift-log")
public class ShiftLogController {

    private final ShiftLogService service;

    public ShiftLogController(ShiftLogService service) {
        this.service = service;
    }

    /**
     * @param kind NOTE (registro) ou HANDOVER (passagem de turno)
     * @param text texto livre; não registrar dado sensível além do necessário
     */
    public record WriteRequest(@NotNull @Pattern(regexp = "NOTE|HANDOVER") String kind,
                               @NotBlank @Size(max = 1000) String text) {
    }

    /**
     * @param id        identificador do registro
     * @param shiftId   turno aberto do autor na hora do registro, se houver
     * @param kind      NOTE ou HANDOVER
     * @param text      texto
     * @param createdAt quando foi escrito
     */
    public record EntryResponse(UUID id, UUID shiftId, String kind, String text, Instant createdAt) {
        static EntryResponse from(ShiftLogEntry e) {
            return new EntryResponse(e.getId(), e.getShiftId(), e.getKind(), e.getText(), e.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse write(@PathVariable UUID condominiumId, @Valid @RequestBody WriteRequest r) {
        return EntryResponse.from(service.write(condominiumId, r.kind(), r.text()));
    }

    @GetMapping
    public PageResponse<EntryResponse> list(@PathVariable UUID condominiumId,
            @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.of(service.list(condominiumId, pageable), EntryResponse::from);
    }
}
