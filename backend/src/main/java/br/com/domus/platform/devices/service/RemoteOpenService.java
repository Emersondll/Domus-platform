package br.com.domus.platform.devices.service;

import br.com.domus.platform.devices.entity.Device;
import br.com.domus.platform.devices.entity.RemoteOpenPolicy;
import br.com.domus.platform.devices.repository.DeviceRepository;
import br.com.domus.platform.devices.repository.RemoteOpenPolicyRepository;
import br.com.domus.platform.shared.audit.AuditOutcome;
import br.com.domus.platform.shared.audit.AuditService;
import br.com.domus.platform.shared.error.ConflictException;
import br.com.domus.platform.shared.tenant.AccessLevel;
import br.com.domus.platform.shared.tenant.AccessService;
import br.com.domus.platform.shared.tenant.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuração da abertura remota pelo morador. É um recurso <b>opcional</b>: nasce desligado e só o síndico (ou
 * administração) o habilita, e ainda assim apenas nos dispositivos explicitamente marcados.
 */
@Service
public class RemoteOpenService {

    private final RemoteOpenPolicyRepository policies;
    private final DeviceRepository devices;
    private final DeviceService deviceService;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;

    public RemoteOpenService(RemoteOpenPolicyRepository policies, DeviceRepository devices,
                             DeviceService deviceService, AccessService access, AuditService audit, Clock clock) {
        this.policies = policies;
        this.devices = devices;
        this.deviceService = deviceService;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID condominiumId) {
        return policies.findById(condominiumId).map(RemoteOpenPolicy::isEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean policy(UUID condominiumId) {
        access.require(condominiumId, AccessLevel.READ, "remote-open.policy.read");
        return isEnabled(condominiumId);
    }

    @Transactional
    public boolean setPolicy(UUID condominiumId, boolean enabled) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "remote-open.policy");
        Instant now = Instant.now(clock);
        policies.findById(condominiumId).ifPresentOrElse(p -> p.change(enabled, actor.userId(), now),
                () -> policies.save(new RemoteOpenPolicy(condominiumId, enabled, actor.userId(), now)));
        audit.record(actor.userId(), null, condominiumId, enabled ? "remote-open.enable" : "remote-open.disable",
                "condominium", condominiumId.toString(), AuditOutcome.SUCCESS);
        return enabled;
    }

    @Transactional
    public Device setDeviceAllowed(UUID condominiumId, UUID deviceId, boolean allowed) {
        Actor actor = access.require(condominiumId, AccessLevel.MANAGE, "remote-open.device");
        Device device = deviceService.find(condominiumId, deviceId);
        if (allowed && !"ACCESS_CONTROLLER".equals(device.getKind())) {
            throw new ConflictException("Only access controllers can be opened by residents");
        }
        device.allowResidentOpen(allowed);
        audit.record(actor.userId(), null, condominiumId, allowed ? "remote-open.device.allow"
                : "remote-open.device.deny", "device", deviceId.toString(), AuditOutcome.SUCCESS);
        return device;
    }

    /** Portas que o morador pode abrir agora: vazio se a política estiver desligada. */
    @Transactional(readOnly = true)
    public List<Device> doorsForResident(UUID condominiumId) {
        access.requireResident(condominiumId, "remote-open.doors");
        if (!isEnabled(condominiumId)) {
            return List.of();
        }
        return devices.findByCondominiumId(condominiumId).stream()
                .filter(d -> d.isResidentOpenable() && "ACCESS_CONTROLLER".equals(d.getKind())).toList();
    }
}
