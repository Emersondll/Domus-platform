-- Histórico de entradas e saídas do próprio morador ("Meus acessos"). Separado de access_attempt (visitantes) de propósito:
-- outra finalidade, outra retenção (portaria.retention.resident-access-days) e outro público (só o titular).
-- Só se grava evento de pessoa cadastrada: referência desconhecida não gera registro pessoal.

-- Identidade de acesso do morador no equipamento (cartão, usuário do terminal, QR, app). A referência é cifrada em nível de
-- campo; a busca por igualdade usa o índice cego (HMAC), como as placas de veículo.
CREATE TABLE access_identity (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL,
    resident_id     UUID         NOT NULL,
    kind            VARCHAR(16)  NOT NULL CHECK (kind IN ('CARD', 'QR', 'TERMINAL_USER', 'APP', 'FACE_REF')),
    external_ref    TEXT         NOT NULL,
    ref_hash        VARCHAR(64)  NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    FOREIGN KEY (resident_id, condominium_id) REFERENCES resident (id, condominium_id)
);
-- Uma referência ativa por condomínio: o mesmo cartão não pode pertencer a duas pessoas ao mesmo tempo.
CREATE UNIQUE INDEX uq_access_identity_active_ref ON access_identity (condominium_id, ref_hash) WHERE active;
CREATE INDEX idx_access_identity_resident ON access_identity (resident_id);

CREATE TABLE resident_access_event (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL,
    resident_id     UUID         NOT NULL,
    device_id       UUID         NOT NULL,
    entrance        VARCHAR(60)  NOT NULL,
    direction       VARCHAR(8)   NOT NULL CHECK (direction IN ('IN', 'OUT', 'UNKNOWN')),
    method          VARCHAR(16)  NOT NULL CHECK (method IN ('CARD', 'QR', 'TERMINAL_USER', 'APP', 'FACE')),
    result          VARCHAR(8)   NOT NULL CHECK (result IN ('GRANTED', 'DENIED')),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (resident_id, condominium_id) REFERENCES resident (id, condominium_id),
    FOREIGN KEY (device_id, condominium_id) REFERENCES device (id, condominium_id)
);
CREATE INDEX idx_resident_access_event_resident ON resident_access_event (resident_id, occurred_at DESC);
CREATE INDEX idx_resident_access_event_received ON resident_access_event (received_at);
