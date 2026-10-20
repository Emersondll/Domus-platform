package br.com.portaria.platform.agent;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a eventos de agentes. */
public interface AgentEventRepository extends JpaRepository<AgentEvent, UUID> {
}
