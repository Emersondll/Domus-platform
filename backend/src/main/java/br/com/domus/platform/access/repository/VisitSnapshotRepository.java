package br.com.domus.platform.access.repository;

import br.com.domus.platform.access.entity.VisitSnapshot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Imagens da câmera de entrada, sempre escopadas por condomínio. */
public interface VisitSnapshotRepository extends JpaRepository<VisitSnapshot, UUID> {

    Optional<VisitSnapshot> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    Optional<VisitSnapshot> findByVisitRequestIdAndCondominiumId(UUID visitRequestId, UUID condominiumId);

    /** Dos pedidos informados, os que têm imagem. */
    @Query("SELECT s.visitRequestId FROM VisitSnapshot s WHERE s.condominiumId = :condominiumId AND s.visitRequestId IN :ids")
    List<UUID> findRequestIdsWithSnapshot(@Param("condominiumId") UUID condominiumId,
                                          @Param("ids") Collection<UUID> ids);
}
