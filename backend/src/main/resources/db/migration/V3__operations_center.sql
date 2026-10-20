-- Fase 4: central de operações (turnos, fila de chamadas, decisões e incidentes).
CREATE TABLE shift (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id),
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_shift_one_open_per_user ON shift (user_id) WHERE ended_at IS NULL;

CREATE TABLE service_call (
    id                UUID PRIMARY KEY,
    condominium_id    UUID         NOT NULL REFERENCES condominium (id),
    unit_id           UUID,
    source            VARCHAR(12)  NOT NULL CHECK (source IN ('MANUAL', 'RESIDENT_APP', 'INTERCOM')),
    caller_name       VARCHAR(160),
    status            VARCHAR(10)  NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'ASSIGNED', 'CLOSED')),
    assigned_user_id  UUID REFERENCES app_user (id),
    created_at        TIMESTAMPTZ  NOT NULL,
    assigned_at       TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,
    outcome           VARCHAR(16)  CHECK (outcome IN ('RESOLVED', 'ENTRY_GRANTED', 'ENTRY_DENIED', 'NO_ANSWER', 'FALSE_ALARM')),
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CHECK ((status = 'QUEUED') = (assigned_user_id IS NULL)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND outcome IS NOT NULL))
);
CREATE INDEX idx_call_queue ON service_call (condominium_id, created_at) WHERE status = 'QUEUED';
CREATE INDEX idx_call_assignee ON service_call (assigned_user_id) WHERE status = 'ASSIGNED';

-- Histórico das decisões e movimentações de cada chamada (append-only por convenção da aplicação).
CREATE TABLE call_event (
    id              UUID PRIMARY KEY,
    call_id         UUID         NOT NULL REFERENCES service_call (id),
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    actor_user_id   UUID,
    type            VARCHAR(20)  NOT NULL,
    detail          VARCHAR(500),
    occurred_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_call_event_call ON call_event (call_id, occurred_at);

CREATE TABLE incident (
    id              UUID PRIMARY KEY,
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    call_id         UUID REFERENCES service_call (id),
    severity        VARCHAR(8)   NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    title           VARCHAR(160) NOT NULL,
    description     VARCHAR(2000),
    status          VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    opened_by       UUID         NOT NULL,
    opened_at       TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ,
    CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);
CREATE INDEX idx_incident_condo_status ON incident (condominium_id, status, opened_at DESC);
