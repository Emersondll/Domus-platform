-- Fase 6: dispositivos, comandos físicos com confirmação verificável e eventos de dispositivo.
CREATE TABLE device (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    name            VARCHAR(80)  NOT NULL,
    kind            VARCHAR(20)  NOT NULL CHECK (kind IN ('ACCESS_CONTROLLER', 'CAMERA', 'INTERCOM')),
    adapter         VARCHAR(20)  NOT NULL CHECK (adapter IN ('SIMULATOR', 'GENERIC_HTTP', 'ONVIF')),
    entrance        VARCHAR(60)  NOT NULL,
    config          TEXT         NOT NULL DEFAULT '{}',
    api_key_hash    VARCHAR(64),
    status          VARCHAR(10)  NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN', 'ONLINE', 'OFFLINE')),
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (condominium_id, name),
    UNIQUE (id, condominium_id)
);

-- Cada comando físico: do pedido à confirmação. CONFIRMED só com evidência do dispositivo/sensor.
CREATE TABLE access_command (
    id                  UUID PRIMARY KEY,
    condominium_id      UUID         NOT NULL,
    device_id           UUID         NOT NULL,
    requested_by        UUID         NOT NULL,
    call_id             UUID REFERENCES service_call (id),
    attempt_id          UUID REFERENCES access_attempt (id),
    justification       VARCHAR(400) NOT NULL,
    idempotency_key     VARCHAR(80)  NOT NULL,
    state               VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'SENT', 'CONFIRMED', 'FAILED', 'UNKNOWN')),
    failure_reason      VARCHAR(200),
    evidence            VARCHAR(200),
    correlation_id      VARCHAR(80),
    requested_at        TIMESTAMPTZ  NOT NULL,
    sent_at             TIMESTAMPTZ,
    resolved_at         TIMESTAMPTZ,
    FOREIGN KEY (device_id, condominium_id) REFERENCES device (id, condominium_id),
    CHECK (call_id IS NOT NULL OR attempt_id IS NOT NULL),
    UNIQUE (condominium_id, idempotency_key)
);
-- Uma validação concedida autoriza no máximo um comando físico.
CREATE UNIQUE INDEX uq_command_attempt ON access_command (attempt_id) WHERE attempt_id IS NOT NULL;
CREATE INDEX idx_command_state ON access_command (state, sent_at) WHERE state IN ('PENDING', 'SENT');

CREATE TABLE device_event (
    id              UUID PRIMARY KEY,
    device_id       UUID         NOT NULL,
    condominium_id  UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('DOOR_OPENED', 'DOOR_CLOSED', 'HEARTBEAT', 'TAMPER', 'ACCESS_DENIED')),
    command_id      UUID REFERENCES access_command (id),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (device_id, condominium_id) REFERENCES device (id, condominium_id)
);
CREATE INDEX idx_device_event_device ON device_event (device_id, received_at DESC);
