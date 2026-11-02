-- Detalhe opcional de um fato auditado (ex.: valor antigo e novo na correção de nome e CNPJ). Nunca guarda dado pessoal de morador.
-- A tabela segue append-only: o gatilho continua bloqueando UPDATE e DELETE.
ALTER TABLE audit_log ADD COLUMN detail VARCHAR(500);
