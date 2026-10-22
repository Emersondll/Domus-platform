-- Criptografia de dados pessoais em nível de campo (AES-GCM na aplicação): colunas passam a TEXT (o valor cifrado é maior que o
-- original) e a placa ganha um índice cego (HMAC) para a unicidade, já que o texto cifrado muda a cada gravação.
ALTER TABLE resident ALTER COLUMN full_name TYPE TEXT;
ALTER TABLE resident ALTER COLUMN email TYPE TEXT;
ALTER TABLE resident ALTER COLUMN phone TYPE TEXT;
DROP INDEX idx_resident_condominium;
CREATE INDEX idx_resident_condominium ON resident (condominium_id);

ALTER TABLE visitor ALTER COLUMN full_name TYPE TEXT;
ALTER TABLE service_call ALTER COLUMN caller_name TYPE TEXT;

ALTER TABLE app_user ALTER COLUMN email TYPE TEXT;
ALTER TABLE app_user ALTER COLUMN display_name TYPE TEXT;
-- A identidade do usuário é o "sub" do provedor (único); o e-mail cifrado não pode ser único nem consultado.
ALTER TABLE app_user DROP CONSTRAINT app_user_email_key;

ALTER TABLE vehicle ALTER COLUMN plate TYPE TEXT;
ALTER TABLE vehicle ADD COLUMN plate_hash VARCHAR(64);
ALTER TABLE vehicle DROP CONSTRAINT vehicle_condominium_id_plate_key;
-- Linhas legadas têm plate_hash nulo até a migração (PiiEncryptionMigration); nulos não colidem entre si.
CREATE UNIQUE INDEX uq_vehicle_condominium_plate_hash ON vehicle (condominium_id, plate_hash);
