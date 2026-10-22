package br.com.domus.platform.commercial.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Empresa operadora de portaria remota: contrata e atende vários condomínios. */
@Entity
@Table(name = "operator_company")
public class OperatorCompany {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "tax_id", nullable = false, unique = true)
    private String taxId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OperatorCompany() {
    }

    public OperatorCompany(String name, String taxId) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.taxId = taxId;
    }

    public UUID getId() {
        return id;
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
