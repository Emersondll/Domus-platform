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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de provisionamento de acessos (vínculo entre a conta do provedor de identidade e o papel de negócio). */
@RestController
@RequestMapping("/api/v1")
public class MemberController {

    private final MemberService service;

    /** Construtor para criação de um(a) member controller. */
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
     * @param displayName    nome de exibição do usuário (só na listagem de equipe; nulo nas demais respostas)
     */
    public record MembershipResponse(UUID id, UUID userId, Role role, UUID condominiumId, UUID companyId,
                                     UUID residentId, String displayName) {
        static MembershipResponse from(Membership m) {
            return from(m, null);
        }

        static MembershipResponse from(Membership m, String displayName) {
            return new MembershipResponse(m.getId(), m.getUserId(), m.getRole(), m.getCondominiumId(),
                    m.getOperatorCompanyId(), m.getResidentId(), displayName);
        }
    }

    /**
     * Atende `POST /companies/{companyId}/admins`.
     *
     * @param companyId empresa operadora
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/companies/{companyId}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse addCompanyAdmin(@PathVariable UUID companyId, @Valid @RequestBody AccountRequest r) {
        return MembershipResponse.from(service.addCompanyAdmin(companyId, r.externalSubject(), r.email(), r.displayName()));
    }

    /**
     * @param membershipId identificador da associação
     * @param userId       usuário
     * @param displayName  nome de exibição
     */
    public record CompanyAdminResponse(UUID membershipId, UUID userId, String displayName) {
    }

    /**
     * Atende `GET /companies/{companyId}/admins`.
     *
     * @param companyId empresa operadora
     */
    @GetMapping("/companies/{companyId}/admins")
    public List<CompanyAdminResponse> listCompanyAdmins(@PathVariable UUID companyId) {
        return service.listCompanyAdmins(companyId).stream()
                .map(a -> new CompanyAdminResponse(a.membership().getId(), a.membership().getUserId(), a.displayName()))
                .toList();
    }

    /**
     * Atende `POST /companies/{companyId}/admins/{membershipId}/revoke`.
     *
     * @param companyId empresa operadora
     * @param membershipId associação do administrador
     */
    @PostMapping("/companies/{companyId}/admins/{membershipId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeCompanyAdmin(@PathVariable UUID companyId, @PathVariable UUID membershipId) {
        service.revokeCompanyAdmin(companyId, membershipId);
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/members`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/condominiums/{condominiumId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse addStaff(@PathVariable UUID condominiumId, @Valid @RequestBody StaffRequest r) {
        return MembershipResponse.from(service.addStaff(condominiumId, Role.valueOf(r.role()),
                r.account().externalSubject(), r.account().email(), r.account().displayName()));
    }

    /**
     * Atende `GET /condominiums/{condominiumId}/members`.
     *
     * @param condominiumId condomínio
     * @param email e-mail
     * @return lista de resultados
     */
    @GetMapping("/condominiums/{condominiumId}/members")
    public List<MembershipResponse> list(@PathVariable UUID condominiumId, @RequestParam(required = false) String email) {
        return service.list(condominiumId, email).stream()
                .map(a -> MembershipResponse.from(a.membership(), a.displayName())).toList();
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/residents/{residentId}/account`.
     *
     * @param condominiumId condomínio
     * @param residentId morador
     * @param r corpo da requisição (validado)
     */
    @PostMapping("/condominiums/{condominiumId}/residents/{residentId}/account")
    @ResponseStatus(HttpStatus.CREATED)
    public MembershipResponse linkResident(@PathVariable UUID condominiumId, @PathVariable UUID residentId,
                                           @Valid @RequestBody AccountRequest r) {
        return MembershipResponse.from(service.linkResident(condominiumId, residentId, r.externalSubject(), r.email(),
                r.displayName()));
    }

    /**
     * Atende `POST /condominiums/{condominiumId}/members/{membershipId}/revoke`.
     *
     * @param condominiumId condomínio
     * @param membershipId associação
     */
    @PostMapping("/condominiums/{condominiumId}/members/{membershipId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID condominiumId, @PathVariable UUID membershipId) {
        service.revoke(condominiumId, membershipId);
    }
}
