package br.com.portaria.platform.residents;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API de autoatendimento do morador. */
@RestController
@RequestMapping("/api/v1")
public class ResidentSelfController {

    private final ResidentSelfService service;

    public ResidentSelfController(ResidentSelfService service) {
        this.service = service;
    }

    /**
     * @param condominiumId   condomínio
     * @param condominiumName nome do condomínio
     * @param unitId          unidade
     * @param unitNumber      número da unidade
     * @param residentId      cadastro de morador
     */
    public record ResidencyResponse(UUID condominiumId, String condominiumName, UUID unitId, String unitNumber,
                                    UUID residentId) {
    }

    /**
     * @param fullName nome completo (somente leitura; alteração é feita pela administração)
     * @param email    e-mail de contato
     * @param phone    telefone de contato
     */
    public record ProfileResponse(String fullName, String email, String phone) {
    }

    /**
     * @param email e-mail de contato
     * @param phone telefone de contato
     */
    public record UpdateContactRequest(@Email @Size(max = 190) String email, @Size(max = 30) String phone) {
    }

    @GetMapping("/me/residencies")
    public List<ResidencyResponse> residencies() {
        return service.residencies().stream().map(r -> new ResidencyResponse(r.condominiumId(),
                r.condominiumName(), r.unitId(), r.unitNumber(), r.residentId())).toList();
    }

    @GetMapping("/condominiums/{condominiumId}/me/profile")
    public ProfileResponse profile(@PathVariable UUID condominiumId) {
        Resident r = service.profile(condominiumId);
        return new ProfileResponse(r.getFullName(), r.getEmail(), r.getPhone());
    }

    @PutMapping("/condominiums/{condominiumId}/me/profile")
    public ProfileResponse updateContact(@PathVariable UUID condominiumId,
                                         @Valid @RequestBody UpdateContactRequest request) {
        Resident r = service.updateContact(condominiumId, request.email(), request.phone());
        return new ProfileResponse(r.getFullName(), r.getEmail(), r.getPhone());
    }
}
