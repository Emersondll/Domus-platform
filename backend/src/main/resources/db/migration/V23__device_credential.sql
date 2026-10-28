-- CR5: usuário e senha da câmera cadastrados pela tela, cifrados em nível de campo (AES-GCM, chave fora do banco).
-- O banco nunca guarda a senha em texto; a API nunca a devolve (só informa se está configurada). Não é um cofre dedicado:
-- quem tiver o banco E a chave de criptografia lê as credenciais (ver docs/security.md).
CREATE TABLE device_credential (
    device_id       UUID PRIMARY KEY,
    condominium_id  UUID NOT NULL,
    username_enc    TEXT NOT NULL,
    password_enc    TEXT NOT NULL,
    updated_by      UUID REFERENCES app_user (id),
    updated_at      TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (device_id, condominium_id) REFERENCES device (id, condominium_id) ON DELETE CASCADE
);
