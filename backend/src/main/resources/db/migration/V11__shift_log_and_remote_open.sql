-- Lote B (2/2): livro de turno e abertura remota pelo morador (opt-in por condomínio e por dispositivo).

-- Livro de turno: registros livres da portaria, por condomínio. O texto é cifrado na aplicação (TEXT):
-- pode citar pessoas. O limite de tamanho é validado antes de cifrar.
CREATE TABLE shift_log_entry (
    id              UUID PRIMARY KEY,
    condominium_id  UUID        NOT NULL REFERENCES condominium (id),
    shift_id        UUID REFERENCES shift (id),
    author_user_id  UUID        NOT NULL REFERENCES app_user (id),
    kind            VARCHAR(10) NOT NULL CHECK (kind IN ('NOTE', 'HANDOVER')),
    text            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_shift_log_condo ON shift_log_entry (condominium_id, created_at DESC);

-- Abertura remota pelo morador: desligada por padrão. Duas travas independentes:
-- a política do condomínio (síndico) e a marca do dispositivo (só portas escolhidas, nunca garagem/veicular por padrão).
CREATE TABLE remote_open_policy (
    condominium_id      UUID PRIMARY KEY REFERENCES condominium (id),
    enabled             BOOLEAN     NOT NULL,
    updated_by_user_id  UUID        NOT NULL REFERENCES app_user (id),
    updated_at          TIMESTAMPTZ NOT NULL
);
ALTER TABLE device ADD COLUMN resident_openable BOOLEAN NOT NULL DEFAULT FALSE;

-- A base de autorização do comando passa a poder ser o próprio morador autenticado (política + dispositivo marcado).
ALTER TABLE access_command ADD COLUMN requested_by_resident BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE access_command DROP CONSTRAINT access_command_check;
ALTER TABLE access_command ADD CONSTRAINT access_command_basis_check
    CHECK (call_id IS NOT NULL OR attempt_id IS NOT NULL OR requested_by_resident);
-- Limite de frequência por solicitante é consultado por (requested_by, requested_at).
CREATE INDEX idx_command_requester ON access_command (requested_by, requested_at DESC);
