package br.com.domus.platform.devices.controller;

import br.com.domus.platform.devices.entity.AccessCommand;
import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.service.AccessCommandService;
import br.com.domus.platform.devices.service.DeviceEventService;
import br.com.domus.platform.devices.service.DeviceService;
import br.com.domus.platform.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** API de dispositivos, comandos físicos e ingestão de eventos. Sem regra de negócio. */
@RestController
@RequestMapping("/api/v1")
public class DeviceController {

    private final DeviceService devices;
    private final AccessCommandService commands;
    private final DeviceEventService events;

    public DeviceController(DeviceService devices, AccessCommandService commands, DeviceEventService events) {
        this.devices = devices;
        this.commands = commands;
        this.events = events;
    }

    /**
     * @param name     nome do equipamento
     * @param kind     ACCESS_CONTROLLER, CAMERA ou INTERCOM
     * @param adapter  SIMULATOR (apenas desenvolvimento), GENERIC_HTTP, ONVIF, DAHUA_CGI ou AGENT_RELAY (via agente local)
     * @param entrance entrada controlada (ex.: portaria, garagem)
     * @param config   configuração não secreta do adaptador (segredos ficam em variáveis de ambiente); {@code direction} = IN ou OUT
     *                 define o sentido do ponto de acesso (sem ele, a passagem do morador fica sem sentido)
     */
    public record RegisterDeviceRequest(@NotBlank @Size(max = 80) String name,
                                        @NotNull @Pattern(regexp = "ACCESS_CONTROLLER|CAMERA|INTERCOM") String kind,
                                        @NotNull @Pattern(regexp = "SIMULATOR|GENERIC_HTTP|ONVIF|AGENT_RELAY|DAHUA_CGI") String adapter,
                                        @NotBlank @Size(max = 60) String entrance, Map<String, String> config) {
    }

    /**
     * @param id         identificador
     * @param name       nome
     * @param kind       tipo
     * @param adapter    adaptador
     * @param entrance   entrada
     * @param status     UNKNOWN, ONLINE ou OFFLINE
     * @param lastSeenAt última vez em que esteve online
     * @param apiKey     chave de API do dispositivo; presente somente na criação ou rotação
     * @param residentOpenable se o morador pode pedir a abertura deste dispositivo (exige a política do condomínio ligada)
     * @param liveVideo        se a câmera tem fonte de vídeo (RTSP) configurada para a visualização ao vivo
     * @param active           falso quando a câmera foi desativada (sem vídeo e fora da câmera de entrada)
     * @param visitorEntrance  se é a câmera de entrada de visitas da sua entrada
     * @param config           configuração não secreta (para câmeras: endereço RTSP e nome da variável de credencial)
     */
    public record DeviceResponse(UUID id, String name, String kind, String adapter, String entrance, String status,
                                 Instant lastSeenAt, String apiKey, boolean residentOpenable, boolean liveVideo,
                                 boolean active, boolean visitorEntrance, Map<String, String> config) {
        public static DeviceResponse from(Device d, String apiKey) {
            return new DeviceResponse(d.getId(), d.getName(), d.getKind(), d.getAdapter(), d.getEntrance(),
                    d.getStatus(), d.getLastSeenAt(), apiKey, d.isResidentOpenable(),
                    "CAMERA".equals(d.getKind()) && d.getConfig().containsKey("rtspUrl"), d.isActive(),
                    d.isVisitorEntrance(), "CAMERA".equals(d.getKind()) ? d.getConfig() : Map.of());
        }
    }

    /**
     * @param callId         atendimento em andamento do operador (uma das bases de autorização)
     * @param attemptId      validação de credencial concedida há menos de 5 minutos (outra base)
     * @param justification  motivo da abertura (mín. 5 caracteres)
     * @param idempotencyKey chave que torna o pedido repetível sem abrir duas vezes
     */
    public record OpenRequest(UUID callId, UUID attemptId, @NotBlank @Size(min = 5, max = 400) String justification,
                              @NotBlank @Size(max = 80) String idempotencyKey) {
    }

    /**
     * @param id            identificador do comando
     * @param deviceId      dispositivo
     * @param state         PENDING, SENT, CONFIRMED, FAILED ou UNKNOWN; somente CONFIRMED indica porta aberta
     * @param failureReason motivo quando FAILED ou UNKNOWN
     * @param evidence      evidência da confirmação
     * @param requestedAt   pedido
     * @param sentAt        aceitação pelo equipamento
     * @param resolvedAt    desfecho
     */
    public record CommandResponse(UUID id, UUID deviceId, String state, String failureReason, String evidence,
                                  Instant requestedAt, Instant sentAt, Instant resolvedAt) {
        public static CommandResponse from(AccessCommand c) {
            return new CommandResponse(c.getId(), c.getDeviceId(), c.getState(), c.getFailureReason(),
                    c.getEvidence(), c.getRequestedAt(), c.getSentAt(), c.getResolvedAt());
        }
    }

    /**
     * @param type       DOOR_OPENED, DOOR_CLOSED, HEARTBEAT, TAMPER ou ACCESS_DENIED
     * @param commandId  comando ao qual o evento responde, opcional
     * @param occurredAt instante em que o dispositivo registrou o evento
     * @param identityRef referência de quem passou no equipamento (cartão, usuário do terminal), opcional; só vira
     *                    "meus acessos" se for de um morador cadastrado
     * @param method      CARD, QR, TERMINAL_USER, APP ou FACE, opcional
     * @param direction   IN ou OUT, opcional; o sentido configurado no dispositivo prevalece
     */
    public record EventRequest(@NotBlank String type, UUID commandId, @NotNull Instant occurredAt,
                               @Size(max = 120) String identityRef,
                               @Pattern(regexp = "CARD|QR|TERMINAL_USER|APP|FACE") String method,
                               @Pattern(regexp = "IN|OUT") String direction) {
    }

    @PostMapping("/condominiums/{condominiumId}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(@PathVariable UUID condominiumId, @Valid @RequestBody RegisterDeviceRequest r) {
        DeviceService.Registered reg = devices.register(condominiumId, r.name(), r.kind(), r.adapter(), r.entrance(),
                r.config());
        return DeviceResponse.from(reg.device(), reg.apiKey());
    }

    @GetMapping("/condominiums/{condominiumId}/devices")
    public PageResponse<DeviceResponse> list(@PathVariable UUID condominiumId,
                                             @PageableDefault(size = 50, sort = "name") Pageable pageable) {
        return PageResponse.of(devices.list(condominiumId, pageable), d -> DeviceResponse.from(d, null));
    }

    /**
     * @param name     novo nome
     * @param entrance nova entrada
     * @param config   nova configuração (substitui a anterior)
     */
    public record UpdateDeviceRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 60) String entrance,
                                      Map<String, String> config) {
    }

    /** @param active verdadeiro reativa, falso desativa */
    public record ActiveRequest(boolean active) {
    }

    /** @param enabled verdadeiro marca como câmera de entrada de visitas; falso desmarca */
    public record VisitorEntranceRequest(boolean enabled) {
    }

    @org.springframework.web.bind.annotation.PutMapping("/condominiums/{condominiumId}/devices/{deviceId}")
    public DeviceResponse update(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                 @Valid @RequestBody UpdateDeviceRequest r) {
        return DeviceResponse.from(devices.update(condominiumId, deviceId, r.name().trim(), r.entrance().trim(),
                r.config()), null);
    }

    @PostMapping("/condominiums/{condominiumId}/devices/{deviceId}/active")
    public DeviceResponse active(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                 @RequestBody ActiveRequest r) {
        return DeviceResponse.from(devices.setActive(condominiumId, deviceId, r.active()), null);
    }

    @PostMapping("/condominiums/{condominiumId}/devices/{deviceId}/visitor-entrance")
    public DeviceResponse visitorEntrance(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                          @RequestBody VisitorEntranceRequest r) {
        return DeviceResponse.from(devices.setVisitorEntrance(condominiumId, deviceId, r.enabled()), null);
    }

    @PostMapping("/condominiums/{condominiumId}/devices/{deviceId}/refresh-status")
    public DeviceResponse refresh(@PathVariable UUID condominiumId, @PathVariable UUID deviceId) {
        return DeviceResponse.from(devices.refreshStatus(condominiumId, deviceId), null);
    }

    @PostMapping("/condominiums/{condominiumId}/devices/{deviceId}/rotate-key")
    public Map<String, String> rotate(@PathVariable UUID condominiumId, @PathVariable UUID deviceId) {
        return Map.of("apiKey", devices.rotateKey(condominiumId, deviceId));
    }

    @PostMapping("/condominiums/{condominiumId}/devices/{deviceId}/open")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommandResponse open(@PathVariable UUID condominiumId, @PathVariable UUID deviceId,
                                @Valid @RequestBody OpenRequest r) {
        return CommandResponse.from(commands.open(condominiumId, deviceId, r.callId(), r.attemptId(),
                r.justification(), r.idempotencyKey()));
    }

    @GetMapping("/condominiums/{condominiumId}/commands/{commandId}")
    public CommandResponse command(@PathVariable UUID condominiumId, @PathVariable UUID commandId) {
        return CommandResponse.from(commands.get(condominiumId, commandId));
    }

    @GetMapping("/condominiums/{condominiumId}/commands")
    public PageResponse<CommandResponse> commandList(@PathVariable UUID condominiumId,
            @PageableDefault(size = 50, sort = "requestedAt", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(commands.list(condominiumId, pageable), CommandResponse::from);
    }

    /** Ingestão de eventos: sem JWT; autenticada pela chave do dispositivo no cabeçalho {@code X-Device-Key}. */
    @PostMapping("/devices/{deviceId}/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@PathVariable UUID deviceId, @RequestHeader(value = "X-Device-Key", required = false) String key,
                       @Valid @RequestBody EventRequest r) {
        events.ingest(deviceId, key, r.type(), r.commandId(), r.occurredAt(),
                new br.com.domus.platform.devices.adapter.DeviceEventSink.Person(r.identityRef(), r.method(),
                        r.direction()));
    }
}
