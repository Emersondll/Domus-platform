package br.com.domus.platform.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Associação de um usuário a um papel e escopo (empresa ou condomínio). Um operador que atende vários
 * condomínios possui uma associação por condomínio: a política é explícita, nunca implícita.
 */
@Entity
@Table(name = "membership")
public class Membership {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "operator_company_id")
    private UUID operatorCompanyId;

    @Column(name = "condominium_id")
    private UUID condominiumId;

    @Column(name = "resident_id")
    private UUID residentId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Membership() {
    }

    public Membership(UUID userId, Role role, UUID operatorCompanyId, UUID condominiumId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.role = role;
        this.operatorCompanyId = operatorCompanyId;
        this.condominiumId = condominiumId;
    }

    /** Cria a associação de um morador autenticado ao seu cadastro de morador. */
    public static Membership forResident(UUID userId, UUID condominiumId, UUID residentId) {
        Membership m = new Membership(userId, Role.RESIDENT, null, condominiumId);
        m.residentId = residentId;
        return m;
    }

    public UUID getResidentId() {
        return residentId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public UUID getOperatorCompanyId() {
        return operatorCompanyId;
    }

    public UUID getCondominiumId() {
        return condominiumId;
    }

    public boolean isActive() {
        return active;
    }

    public void revoke() {
        this.active = false;
    }
}
