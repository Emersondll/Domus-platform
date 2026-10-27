-- B4: o e-mail cifrado não pode ser único nem consultado; ganha um índice cego (HMAC do e-mail normalizado) para restaurar a
-- unicidade e permitir busca por igualdade. Linhas anteriores têm email_hash nulo até o BlindIndexReindexer preenchê-lo
-- (nulos não colidem entre si).
ALTER TABLE app_user ADD COLUMN email_hash VARCHAR(64);
CREATE UNIQUE INDEX uq_app_user_email_hash ON app_user (email_hash);
