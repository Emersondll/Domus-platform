package br.com.domus.platform.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a agentes. */
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    List<Agent> findByCondominiumId(UUID condominiumId);

    Optional<Agent> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
