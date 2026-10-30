package br.com.domus.platform.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Código de adesão de um condomínio: quem o conhece pode pedir para se associar; o síndico decide. */
@Entity
@Table(name = "join_code")
public class JoinCode {

    @Id
    @Column(name = "condominium_id")
    private UUID condominiumId;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected JoinCode() {
    }

    /** Construtor para criação de um(a) join code. */
    public JoinCode(UUID condominiumId, String code) {
        this.condominiumId = condominiumId;
        this.code = code;
    }

    /** Troca o código (o anterior deixa de valer). */
    public void rotate(String newCode) {
        this.code = newCode;
        this.createdAt = Instant.now();
    }

    /** Retorna o condomínio. */
    public UUID getCondominiumId() {
        return condominiumId;
    }

    /** Retorna o código. */
    public String getCode() {
        return code;
    }
}
