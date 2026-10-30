package br.com.domus.platform.devices.service;

import br.com.domus.platform.shared.error.InvalidRequestException;
import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.repository.CameraViewSessionStore;
import br.com.domus.platform.devices.repository.DeviceRepository;
import br.com.domus.platform.devices.adapter.DeviceAdapter;
import br.com.domus.platform.devices.adapter.DeviceAdapters;
import br.com.domus.platform.devices.adapter.HostPolicy;
import br.com.domus.platform.devices.adapter.MediaServer;
import br.com.domus.platform.shared.DeviceHealthLookup;
import br.com.domus.platform.notifications.service.NotificationService;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.error.NotFoundException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cadastro, chaves e verificação de saúde de dispositivos. */
@Service
public class DeviceService implements DeviceHealthLookup {


    /** Dispositivo recém-criado, com a chave de API em claro (exibida somente uma vez). */
    public record Registered(Device device, String apiKey) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeviceRepository devices;
    private final DeviceAdapters adapters;
    private final HostPolicy hostPolicy;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final NotificationService notifications;
    private final CameraViewSessionStore viewSessions;
    private final MediaServer media;

    /** Construtor de DeviceService; recebe as dependências por injeção. */
    public DeviceService(DeviceRepository devices, DeviceAdapters adapters, HostPolicy hostPolicy,
                         AccessService access, AuditService audit, Clock clock, NotificationService notifications,
                         CameraViewSessionStore viewSessions, MediaServer media) {
        this.media = media;
        this.devices = devices;
        this.adapters = adapters;
        this.hostPolicy = hostPolicy;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
        this.viewSessions = viewSessions;
    }

    /**
     * Cadastra um dispositivo e, quando aplicável, devolve a chave gerada uma única vez.
     *
     * @param condominiumId condomínio
     * @param name nome
     * @param kind tipo
     * @param adapterType tipo de adaptador
     * @param entrance ponto de acesso
     * @param config configuração do dispositivo
     */
    @Transactional
    public Registered register(UUID condominiumId, String name, String kind, String adapterType, String entrance,
                               Map<String, String> config) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.register");
        DeviceAdapter adapter = adapters.find(adapterType)
                .orElseThrow(() -> new InvalidRequestException("Adapter not available"));
        if ("ACCESS_CONTROLLER".equals(kind) && !adapter.capabilities().contains(DeviceAdapter.Capability.OPEN_DOOR)) {
            throw new InvalidRequestException("Adapter cannot control doors");
        }
        if (config != null && config.containsKey("rtspUrl")) {
            if (!"CAMERA".equals(kind)) {
                throw new InvalidRequestException("rtspUrl only applies to cameras");
            }
            hostPolicy.requireAllowedRtsp(config.get("rtspUrl"));
        }
        requireValidDirection(config);
        requireNoSecretValues(config);
        requireValidVideoSource(kind, config);
        if (devices.existsByCondominiumIdAndName(condominiumId, name)) {
            throw new ConflictException("Device name already in use");
        }
        String apiKey = newKey();
        Device device = devices.save(new Device(condominiumId, name, kind, adapterType, entrance,
                config == null ? Map.of() : config, sha256(apiKey)));
        audit.record(actor.userId(), null, condominiumId, "device.register", "device", device.getId().toString(),
                AuditOutcome.SUCCESS);
        return new Registered(device, apiKey);
    }

    /**
     * {@code videoSource=AGENT} marca a câmera cujo vídeo o agente local publica (atrás de NAT). Só vale para câmeras e não
     * convive com {@code rtspUrl}: o endereço da câmera fica na rede do condomínio, nunca na central.
     */
    private static void requireValidVideoSource(String kind, Map<String, String> config) {
        String source = config == null ? null : config.get("videoSource");
        if (source == null) {
            return;
        }
        if (!"AGENT".equals(source)) {
            throw new InvalidRequestException("videoSource must be AGENT");
        }
        if (!"CAMERA".equals(kind)) {
            throw new InvalidRequestException("videoSource only applies to cameras");
        }
        if (config.containsKey("rtspUrl")) {
            throw new InvalidRequestException("A camera published by the agent has no rtspUrl on the central");
        }
    }

    /**
     * A configuração é visível na API e no banco em texto claro: segredo não entra nela. Chaves que terminam em {@code Env}
     * guardam só o <b>nome</b> de uma variável de ambiente e são permitidas; qualquer outra chave que pareça senha, token
     * ou segredo é recusada (a senha da câmera tem endpoint próprio e vai cifrada).
     */
    private static void requireNoSecretValues(Map<String, String> config) {
        if (config == null) {
            return;
        }
        for (String key : config.keySet()) {
            if (!key.endsWith("Env") && key.toLowerCase(java.util.Locale.ROOT).matches(".*(pass|secret|token|credential|apikey).*")) {
                throw new InvalidRequestException("Secrets do not belong in the device config; use the credentials endpoint");
            }
        }
    }

    /** O sentido do ponto de acesso, quando informado, é IN (entrada) ou OUT (saída). */
    private static void requireValidDirection(Map<String, String> config) {
        String direction = config == null ? null : config.get("direction");
        if (direction != null && !direction.equals("IN") && !direction.equals("OUT")) {
            throw new InvalidRequestException("direction must be IN or OUT");
        }
    }

    /** Edita nome, entrada e configuração de um equipamento (síndico ou administração). Tipo e adaptador não mudam. */
    @Transactional
    public Device update(UUID condominiumId, UUID deviceId, String name, String entrance, Map<String, String> config) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.update");
        Device device = find(condominiumId, deviceId);
        Map<String, String> next = config == null ? Map.of() : config;
        if (next.containsKey("rtspUrl")) {
            if (!"CAMERA".equals(device.getKind())) {
                throw new InvalidRequestException("rtspUrl only applies to cameras");
            }
            hostPolicy.requireAllowedRtsp(next.get("rtspUrl"));
        }
        requireValidDirection(next);
        requireNoSecretValues(next);
        requireValidVideoSource(device.getKind(), next);
        if (devices.existsByCondominiumIdAndNameAndIdNot(condominiumId, name, deviceId)) {
            throw new ConflictException("Device name already in use");
        }
        boolean entranceChanged = !device.getEntrance().equals(entrance);
        if (device.isVisitorEntrance() && entranceChanged) {
            throw new ConflictException("Unmark the visitor entrance camera before changing its entrance");
        }
        device.update(name, entrance, next);
        audit.record(actor.userId(), null, condominiumId, "device.update", "device", deviceId.toString(),
                AuditOutcome.SUCCESS);
        return device;
    }

    /** Desativa ou reativa uma câmera (equipamentos de acesso não se desativam por aqui). */
    @Transactional
    public Device setActive(UUID condominiumId, UUID deviceId, boolean active) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.active");
        Device device = find(condominiumId, deviceId);
        if (!"CAMERA".equals(device.getKind())) {
            throw new ConflictException("Only cameras can be deactivated");
        }
        device.setActive(active);
        if (!active) {
            viewSessions.revokeByDevice(deviceId, false, "DEVICE_DEACTIVATED", clock.instant());
        }
        audit.record(actor.userId(), null, condominiumId, active ? "device.activate" : "device.deactivate", "device",
                deviceId.toString(), AuditOutcome.SUCCESS);
        return device;
    }

    /** Marca (ou desmarca) a câmera de entrada de visitas; marcar troca a que estava marcada na mesma entrada. */
    @Transactional
    public Device setVisitorEntrance(UUID condominiumId, UUID deviceId, boolean enabled) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.visitor-entrance");
        Device device = find(condominiumId, deviceId);
        if (!"CAMERA".equals(device.getKind())) {
            throw new ConflictException("Only a camera can be the visitor entrance camera");
        }
        if (enabled) {
            if (!device.isActive()) {
                throw new ConflictException("Activate the camera first");
            }
            devices.findByCondominiumIdAndEntranceAndVisitorEntranceTrue(condominiumId, device.getEntrance())
                    .stream().filter(d -> !d.getId().equals(deviceId)).forEach(d -> d.setVisitorEntrance(false));
            devices.flush();
        }
        device.setVisitorEntrance(enabled);
        audit.record(actor.userId(), null, condominiumId, "device.visitor-entrance." + enabled, "device",
                deviceId.toString(), AuditOutcome.SUCCESS);
        return device;
    }

    /** Libera (ou retira) a câmera para o morador ver ao vivo. Sem limite de quantidade nem horário (decisão do titular: 24 horas). */
    @Transactional
    public Device setResidentViewable(UUID condominiumId, UUID deviceId, boolean enabled) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.resident-viewable");
        Device device = find(condominiumId, deviceId);
        if (!"CAMERA".equals(device.getKind())) {
            throw new ConflictException("Only a camera can be shown to residents");
        }
        if (enabled && !device.isResidentViewable()) {
            if (!device.isActive()) {
                throw new ConflictException("Activate the camera first");
            }
        }
        boolean changed = device.isResidentViewable() != enabled;
        device.setResidentViewable(enabled);
        if (changed && !enabled) {
            viewSessions.revokeByDevice(deviceId, true, "RESIDENT_RELEASE_REMOVED", clock.instant());
        }
        if (changed && enabled) {
            notifications.notifyAdultResidents(condominiumId, NotificationService.CAMERA_RELEASED, "Nova câmera disponível",
                    "A câmera \"" + device.getName() + "\" (" + device.getEntrance() + ") foi liberada em Câmeras da entrada.");
        }
        audit.record(actor.userId(), null, condominiumId, "device.resident-viewable." + enabled, "device",
                deviceId.toString(), AuditOutcome.SUCCESS);
        return device;
    }

    /**
     * Gira a chave do dispositivo e devolve o novo valor em claro uma única vez.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     * @return texto resultante
     */
    @Transactional
    public String rotateKey(UUID condominiumId, UUID deviceId) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.rotate-key");
        Device device = find(condominiumId, deviceId);
        String apiKey = newKey();
        device.rotateKey(sha256(apiKey));
        audit.record(actor.userId(), null, condominiumId, "device.rotate-key", "device", deviceId.toString(),
                AuditOutcome.SUCCESS);
        return apiKey;
    }

    /**
     * Lista os dispositivos do condomínio.
     *
     * @param condominiumId condomínio
     * @param pageable paginação
     * @return página de resultados
     */
    @Transactional(readOnly = true)
    public Page<Device> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "device.list");
        return devices.findByCondominiumId(condominiumId, pageable);
    }

    /**
     * Consulta o equipamento e atualiza o estado do dispositivo.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     */
    @Transactional
    public Device refreshStatus(UUID condominiumId, UUID deviceId) {
        access.require(condominiumId, AccessLevel.OPERATE, "device.status");
        Device device = find(condominiumId, deviceId);
        if (device.isAgentPublished()) {
            // a central não alcança a câmera: o estado é "o agente está enviando vídeo agora?", perguntado ao servidor de mídia
            String health;
            try {
                health = media.isPublishing(CameraStreamService.pathOf(deviceId)) ? "ONLINE" : "OFFLINE";
            } catch (DeviceAdapter.DeviceCommunicationException e) {
                health = "UNKNOWN";
            }
            device.observe(health, Instant.now(clock));
            return device;
        }
        DeviceAdapter adapter = adapters.find(device.getAdapter()).orElseThrow(
                () -> new ConflictException("Adapter not available"));
        DeviceAdapter.StatusReport report = adapter.getStatus(descriptor(device));
        device.observe(report.health().name(), Instant.now(clock));
        return device;
    }

    @Override
    @Transactional(readOnly = true)
    public String healthSummary(UUID condominiumId) {
        List<Device> all = devices.findByCondominiumId(condominiumId);
        if (all.isEmpty()) {
            return "NO_DEVICES";
        }
        long online = all.stream().filter(d -> "ONLINE".equals(d.getStatus())).count();
        long offline = all.stream().filter(d -> "OFFLINE".equals(d.getStatus())).count();
        String label = offline > 0 ? "DEGRADED" : online == all.size() ? "ONLINE" : "UNKNOWN";
        return label + " " + (label.equals("DEGRADED") ? offline : online) + "/" + all.size();
    }

    /** Nível do usuário atual neste condomínio (a tela esconde o que o servidor recusaria). Fora do escopo = 404. */
    @Transactional(readOnly = true)
    public AccessLevel myLevel(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.READ, "device.permissions");
        return access.levelFor(access.currentActor(), condominiumId);
    }

    /** Câmeras liberadas para o morador; quem chama já verificou que é morador do condomínio. */
    public List<Device> residentViewable(UUID condominiumId) {
        return devices.findByCondominiumIdAndResidentViewableTrue(condominiumId);
    }

    /** Dispositivo por identificador, sem escopo de condomínio: só para a autorização do servidor de mídia (não é rota de pessoa). */
    public java.util.Optional<Device> findForMedia(UUID deviceId) {
        return devices.findById(deviceId);
    }

    /**
     * Consulta um dispositivo dentro do condomínio.
     *
     * @param condominiumId condomínio
     * @param deviceId dispositivo
     */
    public Device find(UUID condominiumId, UUID deviceId) {
        return devices.findByIdAndCondominiumId(deviceId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Device not found"));
    }

    /**
     * Converte o dispositivo no descritor usado pelos adaptadores.
     *
     * @param d dispositivo
     */
    public static DeviceAdapter.DeviceDescriptor descriptor(Device d) {
        return new DeviceAdapter.DeviceDescriptor(d.getId(), d.getCondominiumId(), d.getEntrance(), d.getConfig());
    }

    /**
     * Devolve as capacidades que o adaptador do dispositivo declara.
     *
     * @param d dispositivo
     */
    public Set<DeviceAdapter.Capability> capabilitiesOf(Device d) {
        return adapters.find(d.getAdapter()).map(DeviceAdapter::capabilities).orElse(Set.of());
    }

    static String newKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
