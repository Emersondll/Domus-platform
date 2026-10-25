-- Lote B (fecho): mensagens individuais entre o morador e a portaria. Uma conversa por morador (usuário) e condomínio.
-- O texto é cifrado na aplicação (TEXT); o limite de tamanho é validado antes de cifrar.
CREATE TABLE direct_message (
    id                UUID PRIMARY KEY,
    condominium_id    UUID        NOT NULL REFERENCES condominium (id),
    resident_user_id  UUID        NOT NULL REFERENCES app_user (id),
    sender_user_id    UUID        NOT NULL REFERENCES app_user (id),
    from_resident     BOOLEAN     NOT NULL,
    text              TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    read_at           TIMESTAMPTZ,
    CONSTRAINT direct_message_sender_check CHECK (NOT from_resident OR sender_user_id = resident_user_id)
);
CREATE INDEX idx_direct_message_thread ON direct_message (condominium_id, resident_user_id, created_at DESC);
CREATE INDEX idx_direct_message_unread ON direct_message (condominium_id, resident_user_id)
    WHERE read_at IS NULL;
