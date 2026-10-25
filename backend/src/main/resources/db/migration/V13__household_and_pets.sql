-- Cadastros do domicílio: cônjuge como vínculo próprio e animais de estimação por unidade.
ALTER TABLE resident DROP CONSTRAINT resident_resident_type_check;
ALTER TABLE resident ADD CONSTRAINT resident_resident_type_check
    CHECK (resident_type IN ('OWNER', 'TENANT', 'SPOUSE', 'DEPENDENT'));

-- Animal de estimação: dado do domicílio, não pessoal. A unidade é do mesmo condomínio (FK composta).
CREATE TABLE pet (
    id              UUID PRIMARY KEY,
    condominium_id  UUID        NOT NULL,
    unit_id         UUID        NOT NULL,
    name            VARCHAR(60) NOT NULL,
    species         VARCHAR(10) NOT NULL CHECK (species IN ('DOG', 'CAT', 'BIRD', 'OTHER')),
    breed           VARCHAR(60),
    notes           VARCHAR(200),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id)
);
CREATE INDEX idx_pet_unit ON pet (condominium_id, unit_id) WHERE active;
