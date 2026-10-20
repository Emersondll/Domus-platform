package br.com.portaria.platform.devices;

import br.com.portaria.platform.devices.adapter.DeviceAdapter;
import br.com.portaria.platform.devices.adapter.DeviceAdapters;
import br.com.portaria.platform.shared.DeviceHealthLookup;
import br.com.portaria.platform.shared.audit.AuditOutcome;
import br.com.portaria.platform.shared.audit.AuditService;
import br.com.portaria.platform.shared.error.ConflictException;
import br.com.portaria.platform.shared.error.NotFoundException;
import br.com.portaria.platform.shared.tenant.AccessLevel;
import br.com.portaria.platform.shared.tenant.AccessService;
import br.com.portaria.platform.shared.tenant.Actor;
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
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, DeviceAdapters adapters, AccessService access,
                         AuditService audit, Clock clock) {
        this.devices = devices;
        this.adapters = adapters;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Registered register(UUID condominiumId, String name, String kind, String adapterType, String entrance,
                               Map<String, String> config) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "device.register");
        DeviceAdapter adapter = adapters.find(adapterType)
                .orElseThrow(() -> new IllegalArgumentException("Adapter not available"));
        if ("ACCESS_CONTROLLER".equals(kind) && !adapter.capabilities().contains(DeviceAdapter.Capability.OPEN_DOOR)) {
            throw new IllegalArgumentException("Adapter cannot control doors");
        }
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

    @Transactional(readOnly = true)
    public Page<Device> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "device.list");
        return devices.findByCondominiumId(condominiumId, pageable);
    }

    @Transactional
    public Device refreshStatus(UUID condominiumId, UUID deviceId) {
        access.require(condominiumId, AccessLevel.OPERATE, "device.status");
        Device device = find(condominiumId, deviceId);
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

    public Device find(UUID condominiumId, UUID deviceId) {
        return devices.findByIdAndCondominiumId(deviceId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Device not found"));
    }

    public static DeviceAdapter.DeviceDescriptor descriptor(Device d) {
        return new DeviceAdapter.DeviceDescriptor(d.getId(), d.getCondominiumId(), d.getEntrance(), d.getConfig());
    }

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
