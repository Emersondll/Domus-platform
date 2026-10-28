package br.com.domus.platform.agent.adapter;

import br.com.domus.platform.agent.entity.Agent;
import br.com.domus.platform.agent.entity.AgentCommand;
import br.com.domus.platform.agent.repository.AgentCommandRepository;
import br.com.domus.platform.agent.repository.AgentRepository;
import br.com.domus.platform.agent.service.AgentService;
import br.com.domus.platform.devices.adapter.DeviceAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispositivo que fica atrás de um agente local. A central não abre conexão para o condomínio: o comando entra numa
 * fila curta e o agente o busca (pull). "Aceito" aqui significa "enfileirado para o agente"; a confirmação continua
 * vindo do evento DOOR_OPENED enviado pelo agente.
 */
@Component
public class AgentRelayAdapter implements DeviceAdapter {

    static final Duration COMMAND_TTL = Duration.ofSeconds(60);

    private final AgentRepository agents;
    private final AgentCommandRepository queue;
    private final Clock clock;

    /** Construtor de AgentRelayAdapter; recebe as dependências por injeção. */
    public AgentRelayAdapter(AgentRepository agents, AgentCommandRepository queue, Clock clock) {
        this.agents = agents;
        this.queue = queue;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "AGENT_RELAY";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.STATUS, Capability.OPEN_DOOR, Capability.EVENTS);
    }

    @Override
    @Transactional(readOnly = true)
    public StatusReport getStatus(DeviceDescriptor device) {
        return agentOf(device)
                .map(a -> a.getLastSeenAt() != null && a.getLastSeenAt().isAfter(Instant.now(clock).minus(
                        AgentService.ONLINE_WINDOW))
                        ? new StatusReport(Health.ONLINE, "agent online")
                        : new StatusReport(Health.OFFLINE, "agent not seen recently"))
                .orElse(new StatusReport(Health.OFFLINE, "agent not configured"));
    }

    @Override
    @Transactional
    public CommandReceipt requestAccess(DeviceDescriptor device, AccessRequest request)
            throws DeviceCommunicationException {
        Agent agent = agentOf(device).orElseThrow(() -> new DeviceCommunicationException("Agent not configured"));
        Instant now = Instant.now(clock);
        queue.save(new AgentCommand(request.commandId(), agent.getId(), device.condominiumId(), device.id(),
                device.entrance(), now, now.plus(COMMAND_TTL)));
        return new CommandReceipt(true, "queued for agent");
    }

    private Optional<Agent> agentOf(DeviceDescriptor device) {
        String id = device.config().get("agentId");
        if (id == null) {
            return Optional.empty();
        }
        try {
            return agents.findByIdAndCondominiumId(UUID.fromString(id), device.condominiumId())
                    .filter(a -> "ACTIVE".equals(a.getStatus()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
