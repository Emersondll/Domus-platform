-- Lote B: saída de visitantes e comunicados do condomínio.

-- Cada entrada concedida pode ter uma saída registrada pela portaria (quem está no local = GRANTED sem saída).
ALTER TABLE access_attempt
    ADD COLUMN exited_at TIMESTAMPTZ,
    ADD COLUMN exit_registered_by_user_id UUID REFERENCES app_user (id),
    ADD CONSTRAINT access_attempt_exit_check CHECK (exited_at IS NULL OR result = 'GRANTED');
CREATE INDEX idx_attempt_onsite ON access_attempt (condominium_id, occurred_at DESC)
    WHERE result = 'GRANTED' AND exited_at IS NULL;

-- Comunicado da portaria/síndico para todos os moradores do condomínio.
CREATE TABLE announcement (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    title           VARCHAR(120) NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    author_user_id  UUID         NOT NULL REFERENCES app_user (id),
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_announcement_condo ON announcement (condominium_id, created_at DESC);
