package br.com.domus.platform.devices.controller;

import br.com.domus.platform.devices.service.DeviceCredentialService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Credenciais das câmeras: só de gravação (a senha nunca é devolvida). Sem regra de negócio. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}/devices/{deviceId}/credentials")
public class DeviceCredentialController {

    private final DeviceCredentialService credentials;

    /** Construtor de DeviceCredentialController; recebe as dependências por injeção. */
    public DeviceCredentialController(DeviceCredentialService credentials) {
        this.credentials = credentials;
    }

    /**
     * @param username usuário da câmera (1 a 64 caracteres, sem espaços nas pontas nem caracteres de controle)
     * @param password senha da câmera (1 a 128 caracteres); só entra, nunca sai
     */
    public record SetCredentialsRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[^\\p{Cntrl}]+$", message = "must not contain control characters") String username,
            @NotBlank @Size(max = 128) @Pattern(regexp = "^[^\\p{Cntrl}]+$", message = "must not contain control characters") String password) {
        /** Oculta a senha caso o registro seja impresso por engano. */
        @Override
        public String toString() {
            return "SetCredentialsRequest[username=" + username + ", password=***]";
        }
    }

    /**
     * @param configured se há credencial cadastrada
     * @param updatedAt  última troca; nulo se não houver
     */
    public record CredentialStatusResponse(boolean configured, Instant updatedAt) {
        static CredentialStatusResponse from(DeviceCredentialService.Status s) {
            return new CredentialStatusResponse(s.configured(), s.updatedAt());
        }
    }

    /**
     * Atende `GET .../devices/{deviceId}/credentials`: informa se há credencial (nunca o valor).
     *
     * @param condominiumId condomínio
     * @param deviceId câmera
     */
    @GetMapping
    public CredentialStatusResponse status(@PathVariable UUID condominiumId, @PathVariable UUID deviceId) {
        return CredentialStatusResponse.from(credentials.status(condominiumId, deviceId));
    }

    /**
     * Atende `PUT .../devices/{deviceId}/credentials`: cadastra ou troca a credencial.
     *
     * @param condominiumId condomínio
     * @param deviceId câmera
     * @param r corpo da requisição (validado)
     */
    @PutMapping
    public CredentialStatusResponse set(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                        @Valid @RequestBody SetCredentialsRequest r) {
        return CredentialStatusResponse.from(credentials.set(condominiumId, deviceId, r.username(), r.password()));
    }

    /**
     * Atende `DELETE .../devices/{deviceId}/credentials`: remove a credencial.
     *
     * @param condominiumId condomínio
     * @param deviceId câmera
     */
    @DeleteMapping
    public CredentialStatusResponse clear(@PathVariable UUID condominiumId, @PathVariable UUID deviceId) {
        return CredentialStatusResponse.from(credentials.clear(condominiumId, deviceId));
    }
}
