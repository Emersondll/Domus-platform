package br.com.domus.platform.identity.repository;

import br.com.domus.platform.identity.entity.Membership;
import br.com.domus.platform.identity.entity.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acesso a associações. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findByUserIdAndActiveTrue(UUID userId);

    boolean existsByRoleAndActiveTrue(Role role);

    List<Membership> findByResidentId(UUID residentId);

    List<Membership> findByCondominiumIdAndActiveTrue(UUID condominiumId);

    java.util.Optional<Membership> findByIdAndCondominiumId(UUID id, UUID condominiumId);

    List<Membership> findByOperatorCompanyIdAndRoleAndActiveTrue(UUID companyId, Role role);

    java.util.Optional<Membership> findByIdAndOperatorCompanyIdAndRole(UUID id, UUID companyId, Role role);

    boolean existsByUserIdAndRoleAndOperatorCompanyIdAndActiveTrue(UUID userId, Role role, UUID companyId);

    boolean existsByUserIdAndRoleAndCondominiumIdAndActiveTrue(UUID userId, Role role, UUID condominiumId);

    /** Operador de plantão: usuário e nome de exibição. */
    interface OnDutyOperator {
        UUID getId();

        String getDisplayName();
    }

    /** Operadores do condomínio que estão com turno aberto agora. */
    @Query(value = """
            SELECT u.id AS id, u.display_name AS displayName
            FROM membership m
            JOIN app_user u ON u.id = m.user_id AND u.active
            JOIN shift s ON s.user_id = u.id AND s.ended_at IS NULL
            WHERE m.role = 'OPERATOR' AND m.active AND m.condominium_id = :condominiumId
            ORDER BY u.display_name
            """, nativeQuery = true)
    List<OnDutyOperator> findOnDutyOperators(@Param("condominiumId") UUID condominiumId);
}
