package br.com.domus.platform.access.repository;

import br.com.domus.platform.access.entity.VisitRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a pedidos de autorização de visitante. */
public interface VisitRequestRepository extends JpaRepository<VisitRequest, UUID> {

    Optional<VisitRequest> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    Page<VisitRequest> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<VisitRequest> findByCondominiumIdAndCallId(UUID condominiumId, UUID callId, Pageable pageable);

    Page<VisitRequest> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);
}
