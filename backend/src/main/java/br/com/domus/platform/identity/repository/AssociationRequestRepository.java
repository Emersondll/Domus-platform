package br.com.domus.platform.identity.repository;

import br.com.domus.platform.identity.entity.AssociationRequest;
import br.com.domus.platform.identity.entity.AssociationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a pedidos de associação. */
public interface AssociationRequestRepository extends JpaRepository<AssociationRequest, UUID> {

    Optional<AssociationRequest> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Lê com trava de escrita: duas decisões simultâneas sobre o mesmo pedido não criam dois moradores. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AssociationRequest r where r.id = :id and r.condominiumId = :condominiumId")
    Optional<AssociationRequest> lockByIdAndCondominiumId(@Param("id") UUID id, @Param("condominiumId") UUID condominiumId);

    List<AssociationRequest> findByCondominiumIdAndStatusOrderByCreatedAtDesc(UUID condominiumId,
                                                                              AssociationStatus status);

    List<AssociationRequest> findByExternalSubjectOrderByCreatedAtDesc(String externalSubject);

    boolean existsByCondominiumIdAndExternalSubjectAndStatus(UUID condominiumId, String externalSubject,
                                                             AssociationStatus status);
}
