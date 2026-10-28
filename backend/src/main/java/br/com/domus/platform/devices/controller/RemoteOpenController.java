package br.com.domus.platform.devices.controller;

import br.com.domus.platform.devices.controller.DeviceController.CommandResponse;
import br.com.domus.platform.devices.controller.DeviceController.DeviceResponse;
import br.com.domus.platform.devices.service.AccessCommandService;
import br.com.domus.platform.devices.service.RemoteOpenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API da abertura remota pelo morador (opcional): configuração pelo síndico e uso pelo morador. */
@RestController
@RequestMapping("/api/v1/condominiums/{condominiumId}")
public class RemoteOpenController {

    private final RemoteOpenService remoteOpen;
    private final AccessCommandService commands;

    /** Construtor de RemoteOpenController; recebe as dependências por injeção. */
    public RemoteOpenController(RemoteOpenService remoteOpen, AccessCommandService commands) {
        this.remoteOpen = remoteOpen;
        this.commands = commands;
    }

    /**
     * @param enabled se o morador pode abrir, pelo app, as portas marcadas
     */
    public record PolicyRequest(boolean enabled) {
    }

    /**
     * @param enabled política vigente (desligada por padrão)
     */
    public record PolicyResponse(boolean enabled) {
    }

    /**
     * @param allowed se este dispositivo pode ser aberto pelo morador
     */
    public record DeviceAllowRequest(boolean allowed) {
    }

    /**
     * @param id       identificador da porta
     * @param name     nome
     * @param entrance entrada
     */
    public record DoorResponse(UUID id, String name, String entrance) {
    }

    /**
     * @param idempotencyKey chave que torna o toque repetível sem abrir duas vezes
     */
    public record ResidentOpenRequest(@NotBlank @Size(max = 80) String idempotencyKey) {
    }

    @GetMapping("/remote-open")
    /**
     * Atende `GET /condominiums/{condominiumId}/remote-open`.
     *
     * @param condominiumId condomínio
     */
    public PolicyResponse policy(@PathVariable UUID condominiumId) {
        return new PolicyResponse(remoteOpen.policy(condominiumId));
    }

    @PutMapping("/remote-open")
    /**
     * Atende `PUT /condominiums/{condominiumId}/remote-open`.
     *
     * @param condominiumId condomínio
     * @param r corpo da requisição (validado)
     */
    public PolicyResponse setPolicy(@PathVariable UUID condominiumId, @RequestBody PolicyRequest r) {
        return new PolicyResponse(remoteOpen.setPolicy(condominiumId, r.enabled()));
    }

    @PutMapping("/devices/{deviceId}/resident-openable")
    /**
     * Atende `PUT /condominiums/{condominiumId}/devices/{deviceId}/resident-openable`.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     * @param r corpo da requisição (validado)
     */
    public DeviceResponse allow(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                @RequestBody DeviceAllowRequest r) {
        return DeviceController.DeviceResponse.from(remoteOpen.setDeviceAllowed(condominiumId, deviceId, r.allowed()),
                null);
    }

    @GetMapping("/me/doors")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/doors`.
     *
     * @param condominiumId condomínio
     * @return lista de resultados
     */
    public List<DoorResponse> myDoors(@PathVariable UUID condominiumId) {
        return remoteOpen.doorsForResident(condominiumId).stream()
                .map(d -> new DoorResponse(d.getId(), d.getName(), d.getEntrance())).toList();
    }

    @PostMapping("/me/doors/{deviceId}/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    /**
     * Atende `POST /condominiums/{condominiumId}/me/doors/{deviceId}/open`.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     * @param r corpo da requisição (validado)
     */
    public CommandResponse open(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                @Valid @RequestBody ResidentOpenRequest r) {
        return CommandResponse.from(commands.openForResident(condominiumId, deviceId, r.idempotencyKey()));
    }

    @GetMapping("/me/commands/{commandId}")
    /**
     * Atende `GET /condominiums/{condominiumId}/me/commands/{commandId}`.
     *
     * @param condominiumId condomínio
     * @param commandId comando
     */
    public CommandResponse myCommand(@PathVariable UUID condominiumId, @PathVariable UUID commandId) {
        return CommandResponse.from(commands.getMine(condominiumId, commandId));
    }
}
