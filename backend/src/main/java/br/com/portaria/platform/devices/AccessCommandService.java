package br.com.portaria.platform.devices;

import br.com.portaria.platform.access.CredentialService;
import br.com.portaria.platform.devices.adapter.DeviceAdapter;
import br.com.portaria.platform.devices.adapter.DeviceAdapters;
import br.com.portaria.platform.operations.OperationsService;
import br.com.portaria.platform.shared.audit.AuditOutcome;
import br.com.portaria.platform.shared.audit.AuditService;
import br.com.portaria.platform.shared.config.CorrelationIdFilter;
import br.com.portaria.platform.shared.error.ConflictException;
import br.com.portaria.platform.shared.error.ForbiddenException;
import br.com.portaria.platform.shared.error.NotFoundException;
import br.com.portaria.platform.shared.tenant.AccessLevel;
import br.com.portaria.platform.shared.tenant.AccessService;
import br.com.portaria.platform.shared.tenant.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comando físico de abertura. Regras: base de autorização obrigatória (atendimento do operador ou validação de
 * credencial recente), justificativa, dispositivo compatível e acessível, idempotência por chave, e nenhum
 * "abriu" sem evidência. O envio ao equipamento acontece fora da transação do banco; o comando é gravado antes,
 * e o resultado depois, para que uma queda no meio deixe rastro (PENDING → UNKNOWN pelo prazo).
 * Não há retry automático do envio: repetir um comando físico às cegas pode abrir a porta duas vezes.
 */
@Service
public class AccessCommandService {

    private static final Logger log = LoggerFactory.getLogger(AccessCommandService.class);

    private final AccessCommandRepository commands;
    private final DeviceService devices;
    private final DeviceAdapters adapters;
    private final OperationsService operations;
    private final CredentialService credentials;
    private final AccessService access;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Duration confirmationTimeout;

    public AccessCommandService(AccessCommandRepository commands, DeviceService devices, DeviceAdapters adapters,
                                OperationsService operations, CredentialService credentials, AccessService access,
                                AuditService audit, Clock clock, PlatformTransactionManager txManager,
                                @Value("${portaria.devices.confirmation-timeout:PT15S}") Duration confirmationTimeout) {
        this.commands = commands;
        this.devices = devices;
        this.adapters = adapters;
        this.operations = operations;
        this.credentials = credentials;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.confirmationTimeout = confirmationTimeout;
    }

    /** Pedido de abertura. @param callId ou @param attemptId é a base de autorização (exatamente um). */
    public AccessCommand open(UUID condominiumId, UUID deviceId, UUID callId, UUID attemptId, String justification,
                              String idempotencyKey) {
        Actor actor = access.require(condominiumId, AccessLevel.OPERATE, "door.open");
        if (justification == null || justification.trim().length() < 5) {
            throw new IllegalArgumentException("Justification required");
        }
        if ((callId == null) == (attemptId == null)) {
            throw new IllegalArgumentException("Provide exactly one of callId or attemptId");
        }
        AccessCommand existing = commands.findByCondominiumIdAndIdempotencyKey(condominiumId, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            return replay(existing, actor, deviceId);
        }
        Device device = devices.find(condominiumId, deviceId);
        DeviceAdapter adapter = adapters.find(device.getAdapter())
                .orElseThrow(() -> new ConflictException("Adapter not available"));
        if (!"ACCESS_CONTROLLER".equals(device.getKind())
                || !adapter.capabilities().contains(DeviceAdapter.Capability.OPEN_DOOR)) {
            throw new ConflictException("Device does not support door control");
        }
        if (callId != null && !operations.isCallAssignedTo(condominiumId, callId, actor.userId())) {
            deny(actor, condominiumId, deviceId, "call-not-assigned");
        }
        if (attemptId != null && !credentials.isFreshGrant(condominiumId, attemptId)) {
            deny(actor, condominiumId, deviceId, "no-fresh-grant");
        }

        AccessCommand command = persistPending(condominiumId, deviceId, actor, callId, attemptId, justification,
                idempotencyKey);
        DeviceAdapter.DeviceDescriptor descriptor = DeviceService.descriptor(device);

        if (adapter.getStatus(descriptor).health() == DeviceAdapter.Health.OFFLINE) {
            commands.markFailed(command.getId(), "DEVICE_OFFLINE", Instant.now(clock));
            auditCommand(actor, condominiumId, command, AuditOutcome.FAILURE);
            return reload(condominiumId, command.getId());
        }
        try {
            DeviceAdapter.CommandReceipt receipt = adapter.requestAccess(descriptor,
                    new DeviceAdapter.AccessRequest(command.getId(), idempotencyKey));
            if (receipt.accepted()) {
                commands.markSent(command.getId(), Instant.now(clock));
            } else {
                commands.markFailed(command.getId(), "REJECTED_BY_DEVICE", Instant.now(clock));
            }
        } catch (DeviceAdapter.DeviceCommunicationException e) {
            // Não sabemos se o equipamento recebeu o comando: UNKNOWN, nunca FAILED nem CONFIRMED.
            log.warn("Device command {} had no delivery proof: {}", command.getId(), e.getMessage());
            commands.markUnknown(command.getId(), "NO_DELIVERY_PROOF", Instant.now(clock));
        }
        AccessCommand result = reload(condominiumId, command.getId());
        auditCommand(actor, condominiumId, result,
                "FAILED".equals(result.getState()) ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS);
        return result;
    }

    @Transactional(readOnly = true)
    public AccessCommand get(UUID condominiumId, UUID commandId) {
        access.require(condominiumId, AccessLevel.READ, "command.read");
        return reload(condominiumId, commandId);
    }

    @Transactional(readOnly = true)
    public Page<AccessCommand> list(UUID condominiumId, Pageable pageable) {
        access.require(condominiumId, AccessLevel.READ, "command.list");
        return commands.findByCondominiumId(condominiumId, pageable);
    }

    /** Comandos sem desfecho após o prazo viram UNKNOWN. Executa periodicamente. */
    @Scheduled(fixedDelayString = "${portaria.devices.expiry-interval-ms:5000}")
    @Transactional
    public int expireUnconfirmed() {
        Instant now = Instant.now(clock);
        int expired = commands.expireUnconfirmed(now.minus(confirmationTimeout), now);
        if (expired > 0) {
            log.warn("{} device command(s) expired without confirmation", expired);
        }
        return expired;
    }

    private AccessCommand persistPending(UUID condominiumId, UUID deviceId, Actor actor, UUID callId,
                                         UUID attemptId, String justification, String idempotencyKey) {
        try {
            return tx.execute(status -> commands.saveAndFlush(new AccessCommand(condominiumId, deviceId,
                    actor.userId(), callId, attemptId, justification.trim(), idempotencyKey,
                    MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now(clock))));
        } catch (DataIntegrityViolationException e) {
            // Idempotência concorrente ou credencial já usada por outro comando.
            AccessCommand winner = commands.findByCondominiumIdAndIdempotencyKey(condominiumId, idempotencyKey)
                    .orElse(null);
            if (winner != null) {
                return replay(winner, actor, deviceId);
            }
            throw new ConflictException("This authorization was already used for another door command");
        }
    }

    private AccessCommand replay(AccessCommand existing, Actor actor, UUID deviceId) {
        if (!existing.getRequestedBy().equals(actor.userId()) || !existing.getDeviceId().equals(deviceId)) {
            throw new ConflictException("Idempotency key already used for a different request");
        }
        return existing;
    }

    private AccessCommand reload(UUID condominiumId, UUID commandId) {
        return commands.findByIdAndCondominiumId(commandId, condominiumId)
                .orElseThrow(() -> new NotFoundException("Command not found"));
    }

    private void deny(Actor actor, UUID condominiumId, UUID deviceId, String why) {
        audit.record(actor.userId(), null, condominiumId, "door.open." + why, "device", deviceId.toString(),
                AuditOutcome.DENIED);
        throw new ForbiddenException("No valid authorization basis for this command");
    }

    private void auditCommand(Actor actor, UUID condominiumId, AccessCommand command, AuditOutcome outcome) {
        audit.record(actor.userId(), null, condominiumId, "door.open", "access_command",
                command.getId().toString(), outcome);
    }
}
