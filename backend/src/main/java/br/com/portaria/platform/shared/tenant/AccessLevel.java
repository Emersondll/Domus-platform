package br.com.portaria.platform.shared.tenant;

/** Nível de acesso do ator sobre os dados administrativos de um condomínio. OPERATE = rotina da portaria. */
public enum AccessLevel {
    NONE, READ, OPERATE, MANAGE;

    public boolean atLeast(AccessLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}
