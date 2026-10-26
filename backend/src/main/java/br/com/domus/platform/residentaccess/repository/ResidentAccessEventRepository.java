package br.com.domus.platform.residentaccess.repository;

import br.com.domus.platform.residentaccess.entity.ResidentAccessEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResidentAccessEventRepository extends JpaRepository<ResidentAccessEvent, UUID> {

    Page<ResidentAccessEvent> findByCondominiumIdAndResidentIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            UUID condominiumId, UUID residentId, Instant from, Instant to, Pageable pageable);

    Optional<ResidentAccessEvent> findFirstByCondominiumIdAndResidentIdOrderByOccurredAtDesc(UUID condominiumId,
                                                                                            UUID residentId);

    List<ResidentAccessEvent> findByCondominiumIdAndResidentIdOrderByOccurredAtDesc(UUID condominiumId, UUID residentId);

    void deleteByCondominiumIdAndResidentId(UUID condominiumId, UUID residentId);
}
