package br.com.domus.platform.identity.controller;

import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import br.com.domus.platform.identity.service.MemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

/** API de provisionamento de acessos (vínculo entre a conta do provedor de identidade e o papel de negócio). */
@RestController
@RequestMapping("/api/v1")
public class MemberController {

    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    /**
     * @param externalSubject claim {@code sub} do usuário no provedor de identidade (Keycloak)
     * @param email           e-mail do usuário
     * @param displayName     nome de exibição
     */
    public record AccountRequest(@NotBlank @Size(max = 190) String externalSubject, @NotBlank @Email @Size(max = 190) String email,
                                 @NotBlank @Size(max = 160) String displayName) {
    }

    /**
     * @param role papel: OPERATOR ou CONDO_MANAGER
     */
    public record StaffRequest(@NotNull @Pattern(regexp = "OPERATOR|CONDO_MANAGER") String role,
                               @NotNull @Valid AccountRequest account) {
    }

    /**
     * @param id             identificador da associação
     * @param userId         usuário
     * @param role           papel
     * @param condominiumId  condomínio, quando aplicável
     * @param companyId      empresa, quando aplicável
     * @param residentId     cadastro de morador, quando aplicável
     */
    public record MembershipResponse(UUID id, UUID userId, Role role, UUID condominiumId, UUID companyId,
                                     UUID residentId) {
        static MembershipResponse from(Membership m) {
            return new MembershipResponse(m.getId(), m.getUserId(), m.getRole(), m.getCondominiumId(),
                    m.getOperatorCompanyId(), m.getResidentId());
        }
    }

    @PostMapping("/companies/{companyId}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse addCompanyAdmin(@PathVariable UUID companyId, @Valid @RequestBody AccountRequest r) {
        return MembershipResponse.from(service.addCompanyAdmin(companyId, r.externalSubject(), r.email(), r.displayName()));
    }

    @PostMapping("/condominiums/{condominiumId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse addStaff(@PathVariable UUID condominiumId, @Valid @RequestBody StaffRequest r) {
        return MembershipResponse.from(service.addStaff(condominiumId, Role.valueOf(r.role()),
                r.account().externalSubject(), r.account().email(), r.account().displayName()));
    }

    @GetMapping("/condominiums/{condominiumId}/members")
    public List<MembershipResponse> list(@PathVariable UUID condominiumId) {
        return service.list(condominiumId).stream().map(MembershipResponse::from).toList();
    }

    @PostMapping("/condominiums/{condominiumId}/residents/{residentId}/account")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse linkResident(@PathVariable UUID condominiumId, @PathVariable UUID residentId,
                                           @Valid @RequestBody AccountRequest r) {
        return MembershipResponse.from(service.linkResident(condominiumId, residentId, r.externalSubject(), r.email(),
                r.displayName()));
    }

    @PostMapping("/condominiums/{condominiumId}/members/{membershipId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID condominiumId, @PathVariable UUID membershipId) {
        service.revoke(condominiumId, membershipId);
    }
}
