package br.com.domus.platform.residents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acesso a moradores, sempre escopado por condomínio. */
public interface ResidentRepository extends JpaRepository<Resident, UUID> {

    Page<Resident> findByCondominiumId(UUID condominiumId, Pageable pageable);

    Optional<Resident> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Usuários (com acesso ao app) vinculados a moradores ativos da unidade. */
    @Query(value = """
            SELECT m.user_id FROM membership m JOIN resident r ON r.id = m.resident_id
            WHERE m.active AND r.active AND m.role = 'RESIDENT'
              AND r.unit_id = :unitId AND r.condominium_id = :condominiumId
            """, nativeQuery = true)
    List<UUID> findUserIdsOfUnit(@Param("unitId") UUID unitId, @Param("condominiumId") UUID condominiumId);
}
