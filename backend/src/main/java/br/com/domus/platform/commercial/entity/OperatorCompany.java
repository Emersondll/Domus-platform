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

    /** Construtor para criação de um(a) operator company. */
    public OperatorCompany(String name, String taxId) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.taxId = taxId;
    }

    /** Retorna o identificador. */
    public UUID getId() {
        return id;
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
}
