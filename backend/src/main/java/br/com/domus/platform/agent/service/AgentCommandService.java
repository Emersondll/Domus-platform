package br.com.domus.platform.agent.service;

import br.com.domus.platform.agent.entity.Agent;
import br.com.domus.platform.agent.entity.AgentCommand;
import br.com.domus.platform.agent.repository.AgentCommandRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entrega ao agente local os comandos físicos pendentes (aceito não é aberto: só o evento do dispositivo confirma). */
@Service
public class AgentCommandService {

    private final AgentService agents;
    private final AgentCommandRepository commandQueue;
    private final Clock clock;

    /** Construtor de AgentCommandService; recebe as dependências por injeção. */
    public AgentCommandService(AgentService agents, AgentCommandRepository commandQueue, Clock clock) {
        this.agents = agents;
        this.commandQueue = commandQueue;
        this.clock = clock;
    }

    /**
     * Autentica o agente e marca como entregues os comandos pendentes e ainda válidos dele, na mesma transação.
     *
     * @param agentId identificador do agente
     * @param apiKey  chave de API apresentada
     * @param backlog eventos ainda não sincronizados, informado pelo agente (opcional)
     * @return comandos entregues nesta chamada
     */
    @Transactional
    public List<AgentCommand> deliverPending(UUID agentId, String apiKey, Integer backlog) {
        Agent agent = agents.authenticate(agentId, apiKey, null, backlog);
        Instant now = Instant.now(clock);
        List<AgentCommand> pending = commandQueue.findPending(agent.getId(), now);
        pending.forEach(c -> c.deliver(now));
        return pending;
    }
}
