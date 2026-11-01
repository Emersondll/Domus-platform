-- Área comum que deixou de existir (reforma, fim do contrato) sai das reservas sem apagar o histórico de quem já reservou.
ALTER TABLE common_area ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
