package br.com.domus.platform.agent.repository;

import br.com.domus.platform.agent.entity.AgentEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a eventos de agentes. */
public interface AgentEventRepository extends JpaRepository<AgentEvent, UUID> {
}
