package br.com.domus.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso aos comandos destinados a agentes. */
public interface AgentCommandRepository extends JpaRepository<AgentCommand, UUID> {

    @Query("select c from AgentCommand c where c.agentId = :agentId and c.deliveredAt is null and c.expiresAt > :now "
            + "order by c.createdAt")
    List<AgentCommand> findPending(@Param("agentId") UUID agentId, @Param("now") Instant now);
}
