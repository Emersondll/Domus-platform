package br.com.domus.platform.identity;

/** Papéis de negócio. O token prova quem é o usuário; o papel vem sempre de {@link Membership}. */
public enum Role {
    /** Administração global da plataforma: gere empresas operadoras, sem acesso a dados de moradores. */
    PLATFORM_ADMIN,
    /** Administra todos os condomínios da sua empresa operadora. */
    COMPANY_ADMIN,
    /** Operador da central: acesso de leitura apenas aos condomínios explicitamente atribuídos. */
    OPERATOR,
    /** Síndico/gestor: administra um condomínio. */
    CONDO_MANAGER,
    /** Morador: sem acesso aos endpoints administrativos. */
    RESIDENT
}
