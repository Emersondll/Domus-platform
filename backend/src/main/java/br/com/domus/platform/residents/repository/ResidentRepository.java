package br.com.domus.platform.residents.repository;

import br.com.domus.platform.residents.entity.Resident;
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

    Page<Resident> findByCondominiumIdAndUnitId(UUID condominiumId, UUID unitId, Pageable pageable);

    /** Moradores ativos de uma unidade (a família), sem paginação: o domicílio é pequeno. */
    List<Resident> findByCondominiumIdAndUnitIdAndActiveTrueOrderByFullName(UUID condominiumId, UUID unitId);

    Optional<Resident> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    /** Usuários (com acesso ao app) vinculados a moradores ativos da unidade. */
    @Query(value = """
            SELECT m.user_id FROM membership m JOIN resident r ON r.id = m.resident_id
            WHERE m.active AND r.active AND m.role = 'RESIDENT'
              AND r.unit_id = :unitId AND r.condominium_id = :condominiumId
            """, nativeQuery = true)
    List<UUID> findUserIdsOfUnit(@Param("unitId") UUID unitId, @Param("condominiumId") UUID condominiumId);

    /** Usuários (com acesso ao app) de todos os moradores ativos do condomínio. */
    @Query(value = """
            SELECT DISTINCT m.user_id FROM membership m JOIN resident r ON r.id = m.resident_id
            WHERE m.active AND r.active AND m.role = 'RESIDENT' AND r.condominium_id = :condominiumId
            """, nativeQuery = true)
    List<UUID> findUserIdsOfCondominium(@Param("condominiumId") UUID condominiumId);

    /** Como {@link #findUserIdsOfCondominium}, mas sem os dependentes (que não recebem avisos de câmera). */
    @Query(value = """
            SELECT DISTINCT m.user_id FROM membership m JOIN resident r ON r.id = m.resident_id
            WHERE m.active AND r.active AND m.role = 'RESIDENT' AND r.resident_type <> 'DEPENDENT'
              AND r.condominium_id = :condominiumId
            """, nativeQuery = true)
    List<UUID> findAdultUserIdsOfCondominium(@Param("condominiumId") UUID condominiumId);
}
