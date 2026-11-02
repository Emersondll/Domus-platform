-- De onde veio a ação auditada: canal (PANEL, RESIDENT_APP, API, SYSTEM) e tipo de cliente (BROWSER, OTHER).
-- Sem IP e sem dado pessoal. O canal é declarado pelo próprio cliente (cabeçalho), então ajuda a distinguir clique de chamada
-- manual, mas NÃO é prova: quem quiser se passar pelo painel consegue. A tabela segue append-only.
ALTER TABLE audit_log ADD COLUMN client_origin VARCHAR(16);
ALTER TABLE audit_log ADD COLUMN client_kind VARCHAR(8);
