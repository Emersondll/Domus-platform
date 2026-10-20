-- Fase 5: notificações in-app e preferências de privacidade do morador.
CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES app_user (id),
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    type            VARCHAR(30)  NOT NULL,
    title           VARCHAR(160) NOT NULL,
    body            VARCHAR(400),
    created_at      TIMESTAMPTZ  NOT NULL,
    read_at         TIMESTAMPTZ
);
CREATE INDEX idx_notification_user ON notification (user_id, condominium_id, created_at DESC);

CREATE TABLE notification_preference (
    user_id                  UUID PRIMARY KEY REFERENCES app_user (id),
    parcels_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    visitor_arrival_enabled  BOOLEAN NOT NULL DEFAULT TRUE
);
