-- Foto de cadastro do morador e o consentimento específico que a autoriza. Recurso desligado por padrão
-- (portaria.resident-photo.enabled). A foto fica cifrada em nível de campo; sem URL pública; só o titular a vê.
CREATE TABLE privacy_consent (
    id                   UUID PRIMARY KEY,
    condominium_id       UUID         NOT NULL,
    resident_id          UUID         NOT NULL,
    purpose              VARCHAR(24)  NOT NULL CHECK (purpose IN ('ACCESS_PHOTO')),
    text_version         VARCHAR(32)  NOT NULL,
    granted_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ,
    granted_by_user_id   UUID         NOT NULL REFERENCES app_user (id),
    UNIQUE (id, condominium_id),
    FOREIGN KEY (resident_id, condominium_id) REFERENCES resident (id, condominium_id)
);
-- No máximo um consentimento vigente por morador e finalidade.
CREATE UNIQUE INDEX uq_privacy_consent_active ON privacy_consent (resident_id, purpose) WHERE revoked_at IS NULL;

CREATE TABLE resident_photo (
    id                    UUID PRIMARY KEY,
    condominium_id        UUID         NOT NULL,
    resident_id           UUID         NOT NULL UNIQUE,
    content               TEXT         NOT NULL,
    captured_at           TIMESTAMPTZ  NOT NULL,
    uploaded_by_user_id   UUID         NOT NULL REFERENCES app_user (id),
    consent_id            UUID         NOT NULL,
    FOREIGN KEY (resident_id, condominium_id) REFERENCES resident (id, condominium_id),
    FOREIGN KEY (consent_id, condominium_id) REFERENCES privacy_consent (id, condominium_id)
);
