package br.com.domus.platform.residentaccess.controller;

import br.com.domus.platform.residentaccess.entity.AccessIdentity;
import br.com.domus.platform.residentaccess.entity.ResidentAccessEvent;
import br.com.domus.platform.residentaccess.service.ResidentAccessService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Identidades de acesso dos moradores (administração) e o histórico de acessos do próprio morador. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class ResidentAccessController {

    private final ResidentAccessService service;

    public ResidentAccessController(ResidentAccessService service) {
        this.service = service;
    }

    /**
     * @param kind CARD, QR, TERMINAL_USER ou APP
     * @param ref  referência da pessoa no equipamento; é guardada cifrada e nunca é devolvida pela API
     */
    public record RegisterIdentityRequest(@NotNull @Pattern(regexp = "CARD|QR|TERMINAL_USER|APP") String kind,
                                          @NotBlank @Size(max = 120) String ref) {
    }

    /**
     * @param id        identificador
     * @param kind      meio de identificação
     * @param active    falso depois de revogada
     * @param createdAt cadastro
     * @param revokedAt revogação, quando houver
     */
    public record IdentityResponse(UUID id, String kind, boolean active, Instant createdAt, Instant revokedAt) {
        static IdentityResponse from(AccessIdentity i) {
            return new IdentityResponse(i.getId(), i.getKind(), i.isActive(), i.getCreatedAt(), i.getRevokedAt());
        }
    }

    /**
     * @param id         identificador do registro
     * @param occurredAt instante da passagem
     * @param direction  IN (entrada), OUT (saída) ou UNKNOWN (passagem sem sentido confiável)
     * @param entrance   ponto de acesso
     * @param method     CARD, QR, TERMINAL_USER, APP ou FACE
     * @param result     GRANTED ou DENIED
     */
    public record AccessResponse(UUID id, Instant occurredAt, String direction, String entrance, String method,
                                 String result) {
        static AccessResponse from(ResidentAccessEvent e) {
            return new AccessResponse(e.getId(), e.getOccurredAt(), e.getDirection(), e.getEntrance(), e.getMethod(),
                    e.getResult());
        }
    }

    /** @param last último acesso, ou {@code null} quando ainda não há registro */
    public record LastAccessResponse(AccessResponse last) {
    }

    @PostMapping("/residents/{residentId}/access-identities")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityResponse register(@PathVariable UUID condominiumId, @PathVariable UUID residentId,
                                     @Valid @RequestBody RegisterIdentityRequest r) {
        return IdentityResponse.from(service.register(condominiumId, residentId, r.kind(), r.ref()));
    }

    @GetMapping("/residents/{residentId}/access-identities")
    public List<IdentityResponse> identities(@PathVariable UUID condominiumId, @PathVariable UUID residentId) {
        return service.listIdentities(condominiumId, residentId).stream().map(IdentityResponse::from).toList();
    }

    @PostMapping("/residents/{residentId}/access-identities/{identityId}/revoke")
    public IdentityResponse revoke(@PathVariable UUID condominiumId, @PathVariable UUID residentId,
                                   @PathVariable UUID identityId) {
        return IdentityResponse.from(service.revoke(condominiumId, residentId, identityId));
    }

    /**
     * Histórico de acessos do próprio morador. Período de até 31 dias (padrão: últimos 7); mais recente primeiro.
     */
    @GetMapping("/me/accesses")
    public PageResponse<AccessResponse> mine(@PathVariable UUID condominiumId,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(service.myAccesses(condominiumId, from, to, page, size), AccessResponse::from);
    }

    @GetMapping("/me/accesses/last")
    public ResponseEntity<LastAccessResponse> last(@PathVariable UUID condominiumId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new LastAccessResponse(service.myLast(condominiumId).map(AccessResponse::from).orElse(null)));
    }
}
