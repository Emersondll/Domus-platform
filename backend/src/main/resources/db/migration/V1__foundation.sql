-- Fundação: tenancy (empresa operadora -> condomínio -> bloco -> unidade -> morador),
-- identidade/associações e auditoria append-only.

CREATE TABLE operator_company (
    id          UUID PRIMARY KEY,
    name        VARCHAR(160) NOT NULL,
    tax_id      VARCHAR(20)  NOT NULL UNIQUE,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE condominium (
    id                   UUID PRIMARY KEY,
    operator_company_id  UUID         NOT NULL REFERENCES operator_company (id),
    name                 VARCHAR(160) NOT NULL,
    tax_id               VARCHAR(20),
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (operator_company_id, name)
);
CREATE INDEX idx_condominium_company ON condominium (operator_company_id);

CREATE TABLE app_user (
    id                UUID PRIMARY KEY,
    external_subject  VARCHAR(190) NOT NULL UNIQUE,
    email             VARCHAR(190) NOT NULL UNIQUE,
    display_name      VARCHAR(160) NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Autorização de negócio, separada da autenticação (o token só prova quem é o usuário).
-- Escopo por papel:
--   PLATFORM_ADMIN              -> sem escopo
--   COMPANY_ADMIN               -> operator_company_id
--   OPERATOR / CONDO_MANAGER    -> condominium_id explícito (operador multicondomínio = várias linhas)
--   RESIDENT                    -> condominium_id
CREATE TABLE membership (
    id                   UUID PRIMARY KEY,
    user_id              UUID        NOT NULL REFERENCES app_user (id),
    role                 VARCHAR(24) NOT NULL
        CHECK (role IN ('PLATFORM_ADMIN', 'COMPANY_ADMIN', 'OPERATOR', 'CONDO_MANAGER', 'RESIDENT')),
    operator_company_id  UUID REFERENCES operator_company (id),
    condominium_id       UUID REFERENCES condominium (id),
    active               BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT membership_scope CHECK (
        (role = 'PLATFORM_ADMIN' AND operator_company_id IS NULL AND condominium_id IS NULL)
        OR (role = 'COMPANY_ADMIN' AND operator_company_id IS NOT NULL AND condominium_id IS NULL)
        OR (role IN ('OPERATOR', 'CONDO_MANAGER', 'RESIDENT')
            AND condominium_id IS NOT NULL AND operator_company_id IS NULL)
    )
);
CREATE INDEX idx_membership_user ON membership (user_id) WHERE active;

CREATE TABLE block (
    id              UUID PRIMARY KEY,
    condominium_id  UUID        NOT NULL REFERENCES condominium (id),
    name            VARCHAR(60) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (condominium_id, name),
    UNIQUE (id, condominium_id)
);

CREATE TABLE unit (
    id              UUID PRIMARY KEY,
    condominium_id  UUID        NOT NULL,
    block_id        UUID        NOT NULL,
    number          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (block_id, number),
    UNIQUE (id, condominium_id),
    -- Integridade no banco: a unidade só pode apontar para bloco do mesmo condomínio.
    FOREIGN KEY (block_id, condominium_id) REFERENCES block (id, condominium_id)
);
CREATE INDEX idx_unit_condominium ON unit (condominium_id);

CREATE TABLE resident (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL,
    unit_id         UUID         NOT NULL,
    full_name       VARCHAR(160) NOT NULL,
    email           VARCHAR(190),
    phone           VARCHAR(30),
    resident_type   VARCHAR(16)  NOT NULL CHECK (resident_type IN ('OWNER', 'TENANT', 'DEPENDENT')),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id)
);
CREATE INDEX idx_resident_condominium ON resident (condominium_id, full_name);
CREATE INDEX idx_resident_unit ON resident (unit_id);

-- Auditoria append-only: UPDATE e DELETE são bloqueados por trigger.
CREATE TABLE audit_log (
    id                   UUID PRIMARY KEY,
    occurred_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_user_id        UUID,
    operator_company_id  UUID,
    condominium_id       UUID,
    action               VARCHAR(80)  NOT NULL,
    resource_type        VARCHAR(60),
    resource_id          VARCHAR(80),
    outcome              VARCHAR(16)  NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    correlation_id       VARCHAR(80),
    details              JSONB
);
CREATE INDEX idx_audit_condominium_time ON audit_log (condominium_id, occurred_at DESC);

CREATE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
