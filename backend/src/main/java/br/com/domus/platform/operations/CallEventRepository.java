package br.com.domus.platform.operations;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso ao histórico de atendimentos. */
public interface CallEventRepository extends JpaRepository<CallEvent, UUID> {

    List<CallEvent> findByCallIdAndCondominiumIdOrderByOccurredAtAsc(UUID callId, UUID condominiumId);
}
