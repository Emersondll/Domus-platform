package br.com.portaria.platform.commercial;

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

    public Condominium(UUID operatorCompanyId, String name, String taxId) {
        this.id = UUID.randomUUID();
        this.operatorCompanyId = operatorCompanyId;
        this.name = name;
        this.taxId = taxId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOperatorCompanyId() {
        return operatorCompanyId;
    }

    public String getName() {
        return name;
    }

    public String getTaxId() {
        return taxId;
    }

    public String getStatus() {
        return status;
    }
}
