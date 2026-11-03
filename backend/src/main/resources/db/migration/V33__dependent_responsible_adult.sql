-- Dependente ligado a um adulto responsável da mesma unidade (opcional). Serve para levar só os dependentes certos numa mudança.
ALTER TABLE resident ADD COLUMN responsible_resident_id UUID;
ALTER TABLE resident ADD CONSTRAINT resident_responsible_fk
    FOREIGN KEY (responsible_resident_id, condominium_id) REFERENCES resident (id, condominium_id);
CREATE INDEX idx_resident_responsible ON resident (responsible_resident_id) WHERE responsible_resident_id IS NOT NULL;
