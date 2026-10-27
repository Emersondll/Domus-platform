package br.com.domus.platform.reservations.repository;

import br.com.domus.platform.reservations.entity.CommonArea;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** Acesso a áreas comuns, sempre escopado por condomínio. */
public interface CommonAreaRepository extends JpaRepository<CommonArea, UUID> {

    Page<CommonArea> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<CommonArea> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Igual a {@link #findByIdAndCondominiumId}, mas trava a linha da área até o fim da transação (serializa reservas da mesma área). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CommonArea> findWithLockByIdAndCondominiumId(UUID id, UUID condominiumId);

    boolean existsByCondominiumIdAndName(UUID condominiumId, String name);
}
