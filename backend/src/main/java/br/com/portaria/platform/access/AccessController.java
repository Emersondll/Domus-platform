package br.com.portaria.platform.access;

import br.com.portaria.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** API de convites do morador, validação de credenciais na portaria e consulta de visitantes/tentativas. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class AccessController {

    private final CredentialService service;

    public AccessController(CredentialService service) {
        this.service = service;
    }

    /**
     * @param visitorName nome do visitante ou prestador
     * @param kind        VISITOR ou SERVICE_PROVIDER
     * @param validFrom   início da validade
     * @param validUntil  fim da validade (no máximo 7 dias após o início)
     * @param maxUses     quantidade máxima de usos (1 a 20); 1 = uso único
     */
    public record CreateInvitationRequest(@NotBlank @Size(max = 160) String visitorName,
                                          @NotNull @Pattern(regexp = "VISITOR|SERVICE_PROVIDER") String kind,
                                          @NotNull Instant validFrom, @NotNull Instant validUntil,
                                          @Min(1) @Max(20) int maxUses) {
    }

    /**
     * @param id          identificador da credencial
     * @param visitorName visitante associado
     * @param validFrom   início da validade
     * @param validUntil  fim da validade
     * @param maxUses     usos permitidos
     * @param useCount    usos consumidos
     * @param status      ACTIVE ou REVOKED
     * @param token       conteúdo do QR Code; presente somente na criação
     */
    public record InvitationResponse(UUID id, String visitorName, Instant validFrom, Instant validUntil,
                                     int maxUses, int useCount, String status, String token) {
    }

    /**
     * @param token    conteúdo lido do QR Code
     * @param entrance identificação da entrada (portaria, garagem, etc.)
     */
    public record ValidateRequest(@NotBlank @Size(max = 200) String token,
                                  @NotBlank @Size(max = 60) String entrance) {
    }

    /**
     * @param granted     se o acesso foi autorizado e um uso consumido
     * @param reason      OK ou o motivo da negação
     * @param unitId      unidade visitada, quando autorizado
     * @param visitorName visitante, quando autorizado
     */
    public record ValidateResponse(boolean granted, String reason, UUID unitId, String visitorName) {
    }

    /**
     * @param id           identificador da tentativa
     * @param credentialId credencial usada, se identificada
     * @param entrance     entrada
     * @param result       GRANTED ou DENIED
     * @param reason       motivo
     * @param occurredAt   instante da tentativa
     */
    public record AttemptResponse(UUID id, UUID credentialId, String entrance, String result, String reason,
                                  Instant occurredAt) {
        static AttemptResponse from(AccessAttempt a) {
            return new AttemptResponse(a.getId(), a.getCredentialId(), a.getEntrance(), a.getResult(),
                    a.getReason(), a.getOccurredAt());
        }
    }

    /**
     * @param id       identificador do visitante
     * @param unitId   unidade visitada
     * @param fullName nome
     * @param kind     VISITOR ou SERVICE_PROVIDER
     */
    public record VisitorResponse(UUID id, UUID unitId, String fullName, String kind) {
        static VisitorResponse from(Visitor v) {
            return new VisitorResponse(v.getId(), v.getUnitId(), v.getFullName(), v.getKind());
        }
    }

    @PostMapping("/me/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse createInvitation(@PathVariable UUID condominiumId,
                                               @Valid @RequestBody CreateInvitationRequest r) {
        CredentialService.Issued issued = service.issueInvitation(condominiumId, r.visitorName(), r.kind(),
                r.validFrom(), r.validUntil(), r.maxUses());
        return toResponse(issued.credential(), issued.visitor().getFullName(), issued.token());
    }

    @GetMapping("/me/invitations")
    public PageResponse<InvitationResponse> listInvitations(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(service.listMine(condominiumId, pageable), c -> toResponse(c, null, null));
    }

    @PostMapping("/me/invitations/{credentialId}/revoke")
    public InvitationResponse revokeInvitation(@PathVariable UUID condominiumId, @PathVariable UUID credentialId) {
        return toResponse(service.revokeMine(condominiumId, credentialId), null, null);
    }

    @PostMapping("/access/validate")
    public ValidateResponse validate(@PathVariable UUID condominiumId, @Valid @RequestBody ValidateRequest r) {
        CredentialService.Validation v = service.validate(condominiumId, r.token(), r.entrance());
        return new ValidateResponse(v.granted(), v.reason(), v.unitId(), v.visitorName());
    }

    @GetMapping("/access/attempts")
    public PageResponse<AttemptResponse> attempts(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "occurredAt") Pageable pageable) {
        return PageResponse.of(service.listAttempts(condominiumId, pageable), AttemptResponse::from);
    }

    @GetMapping("/visitors")
    public PageResponse<VisitorResponse> visitors(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return PageResponse.of(service.listVisitors(condominiumId, pageable), VisitorResponse::from);
    }

    private static InvitationResponse toResponse(AccessCredential c, String visitorName, String token) {
        return new InvitationResponse(c.getId(), visitorName, c.getValidFrom(), c.getValidUntil(), c.getMaxUses(),
                c.getUseCount(), c.getStatus(), token);
    }
}
