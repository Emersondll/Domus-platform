-- Fase 3: veículos, visitantes, encomendas, áreas comuns/reservas, credenciais de acesso e tentativas.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Morador autenticado: associação RESIDENT aponta para o cadastro do morador (mesmo condomínio, via FK composta).
ALTER TABLE resident ADD CONSTRAINT resident_id_condo_uk UNIQUE (id, condominium_id);
ALTER TABLE membership ADD COLUMN resident_id UUID;
ALTER TABLE membership ADD CONSTRAINT membership_resident_fk
    FOREIGN KEY (resident_id, condominium_id) REFERENCES resident (id, condominium_id);
ALTER TABLE membership ADD CONSTRAINT membership_resident_ck
    CHECK ((role = 'RESIDENT') = (resident_id IS NOT NULL));

CREATE TABLE vehicle (
    id              UUID PRIMARY KEY,
    condominium_id  UUID        NOT NULL,
    unit_id         UUID        NOT NULL,
    plate           VARCHAR(10) NOT NULL,
    model           VARCHAR(60),
    color           VARCHAR(30),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (condominium_id, plate),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id)
);
CREATE INDEX idx_vehicle_unit ON vehicle (unit_id);

CREATE TABLE visitor (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL,
    unit_id         UUID         NOT NULL,
    full_name       VARCHAR(160) NOT NULL,
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('VISITOR', 'SERVICE_PROVIDER')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, condominium_id),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id)
);
CREATE INDEX idx_visitor_condo ON visitor (condominium_id, created_at DESC);

CREATE TABLE parcel (
    id                   UUID PRIMARY KEY,
    condominium_id       UUID         NOT NULL,
    unit_id              UUID         NOT NULL,
    carrier              VARCHAR(80),
    description          VARCHAR(200),
    status               VARCHAR(12)  NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PICKED_UP')),
    received_at          TIMESTAMPTZ  NOT NULL,
    received_by_user_id  UUID         NOT NULL,
    picked_up_at         TIMESTAMPTZ,
    picked_up_by_name    VARCHAR(160),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CHECK ((status = 'PICKED_UP') = (picked_up_at IS NOT NULL))
);
CREATE INDEX idx_parcel_unit_status ON parcel (condominium_id, unit_id, status);

CREATE TABLE common_area (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    name            VARCHAR(80)  NOT NULL,
    capacity        INTEGER      NOT NULL CHECK (capacity > 0),
    UNIQUE (condominium_id, name),
    UNIQUE (id, condominium_id)
);

CREATE TABLE reservation (
    id              UUID PRIMARY KEY,
    condominium_id  UUID        NOT NULL,
    area_id         UUID        NOT NULL,
    unit_id         UUID        NOT NULL,
    start_at        TIMESTAMPTZ NOT NULL,
    end_at          TIMESTAMPTZ NOT NULL,
    status          VARCHAR(12) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_at > start_at),
    FOREIGN KEY (area_id, condominium_id) REFERENCES common_area (id, condominium_id),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    -- Sem sobreposição de reservas confirmadas na mesma área, mesmo sob concorrência.
    CONSTRAINT reservation_no_overlap EXCLUDE USING gist (
        area_id WITH =, tstzrange(start_at, end_at) WITH &&) WHERE (status = 'CONFIRMED')
);
CREATE INDEX idx_reservation_area_time ON reservation (area_id, start_at);

CREATE TABLE access_credential (
    id                 UUID PRIMARY KEY,
    condominium_id     UUID        NOT NULL,
    unit_id            UUID        NOT NULL,
    visitor_id         UUID,
    valid_from         TIMESTAMPTZ NOT NULL,
    valid_until        TIMESTAMPTZ NOT NULL,
    max_uses           INTEGER     NOT NULL CHECK (max_uses > 0),
    use_count          INTEGER     NOT NULL DEFAULT 0,
    status             VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_by_user_id UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at         TIMESTAMPTZ,
    CHECK (valid_until > valid_from),
    CHECK (use_count >= 0 AND use_count <= max_uses),
    UNIQUE (id, condominium_id),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    FOREIGN KEY (visitor_id, condominium_id) REFERENCES visitor (id, condominium_id)
);
CREATE INDEX idx_credential_unit ON access_credential (condominium_id, unit_id, created_at DESC);

CREATE TABLE access_attempt (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    credential_id   UUID,
    entrance        VARCHAR(60)  NOT NULL,
    result          VARCHAR(8)   NOT NULL CHECK (result IN ('GRANTED', 'DENIED')),
    reason          VARCHAR(30)  NOT NULL,
    actor_user_id   UUID,
    correlation_id  VARCHAR(80),
    occurred_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_attempt_condo_time ON access_attempt (condominium_id, occurred_at DESC);
