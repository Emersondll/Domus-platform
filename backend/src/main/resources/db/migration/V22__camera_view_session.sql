-- B12: o token do vídeo deixa de valer só por assinatura e prazo. Cada sessão de visualização passa a existir no banco, então
-- pode ser revogada (câmera desativada ou retirada do morador, vínculo encerrado) e limitada por usuário. O servidor de mídia
-- confere a sessão a cada requisição. A tabela não guarda o token nem endereço de câmera.
CREATE TABLE camera_view_session (
    id              UUID PRIMARY KEY,
    condominium_id  UUID NOT NULL,
    device_id       UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES app_user (id),
    kind            VARCHAR(10) NOT NULL CHECK (kind IN ('STAFF', 'RESIDENT')),
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(40),
    FOREIGN KEY (device_id, condominium_id) REFERENCES device (id, condominium_id) ON DELETE CASCADE
);
CREATE INDEX idx_camera_view_session_user ON camera_view_session (user_id, created_at DESC) WHERE revoked_at IS NULL;
CREATE INDEX idx_camera_view_session_device ON camera_view_session (device_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_camera_view_session_expiry ON camera_view_session (expires_at);
