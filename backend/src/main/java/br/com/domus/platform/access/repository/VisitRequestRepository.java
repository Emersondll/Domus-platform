package br.com.domus.platform.access.repository;

import br.com.domus.platform.access.entity.VisitRequest;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Acesso a pedidos de autorização de visitante. */
public interface VisitRequestRepository extends JpaRepository<VisitRequest, UUID> {

    Optional<VisitRequest> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha até o fim da transação (decisões e entradas do mesmo pedido não se atropelam). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VisitRequest> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);

    Page<VisitRequest> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Page<VisitRequest> findByCondominiumIdAndCallId(UUID condominiumId, UUID callId, Pageable pageable);

    Page<VisitRequest> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);
}
