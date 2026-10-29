package br.com.domus.platform.commercial.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Condomínio atendido por uma empresa operadora; raiz do isolamento de dados. */
@Entity
@Table(name = "condominium")
public class Condominium {

    private static final String SUSPENDED = "SUSPENDED";

    @Id
    private UUID id;

    @Column(name = "operator_company_id", nullable = false, updatable = false)
    private UUID operatorCompanyId;

    @Column(nullable = false)
    private String name;

    @Column(name = "tax_id")
    private String taxId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Condominium() {
    }

    /** Construtor para criação de um(a) condominium. */
    public Condominium(UUID operatorCompanyId, String name, String taxId) {
        this.id = UUID.randomUUID();
        this.operatorCompanyId = operatorCompanyId;
        this.name = name;
        this.taxId = taxId;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
    }

    /** Retorna o campo operator company id. */
    public UUID getOperatorCompanyId() {
        return operatorCompanyId;
    }

    /** Retorna o nome. */
    public String getName() {
        return name;
    }

    /** Retorna o campo tax id. */
    public String getTaxId() {
        return taxId;
    }

    /** Retorna o estado atual. */
    public String getStatus() {
        return status;
    }

    /** Verdadeiro quando o acesso está suspenso (os registros ficam; só o acesso é desligado). */
    public boolean isSuspended() {
        return SUSPENDED.equals(status);
    }

    /** Suspende ou reativa; os registros nunca são apagados. */
    public void setSuspended(boolean suspended) {
        this.status = suspended ? SUSPENDED : "ACTIVE";
    }
}
