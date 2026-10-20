package br.com.portaria.platform.shared.tenant;

/** Nível de acesso do ator sobre os dados administrativos de um condomínio. */
public enum AccessLevel {
    NONE, READ, MANAGE;

    public boolean atLeast(AccessLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}
