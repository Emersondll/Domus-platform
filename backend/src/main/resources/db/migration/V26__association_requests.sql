-- Associação do morador ao condomínio: o síndico gera um código de adesão; a pessoa (já autenticada no provedor de identidade)
-- pede para entrar e o síndico aprova ou recusa. Nada vira acesso antes da aprovação.
CREATE TABLE join_code (
    condominium_id UUID PRIMARY KEY REFERENCES condominium (id),
    code           VARCHAR(12) NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE association_request (
    id               UUID PRIMARY KEY,
    condominium_id   UUID         NOT NULL REFERENCES condominium (id),
    external_subject VARCHAR(255) NOT NULL,
    full_name        TEXT         NOT NULL,
    email            TEXT         NOT NULL,
    phone            TEXT,
    requested_type   VARCHAR(16)  NOT NULL CHECK (requested_type IN ('OWNER', 'TENANT')),
    unit_text        VARCHAR(60)  NOT NULL,
    status           VARCHAR(12)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    decided_by       UUID,
    resident_id      UUID
);
CREATE UNIQUE INDEX uq_association_request_pending ON association_request (condominium_id, external_subject)
    WHERE status = 'PENDING';
CREATE INDEX idx_association_request_condominium ON association_request (condominium_id, status, created_at);
CREATE INDEX idx_association_request_subject ON association_request (external_subject);
