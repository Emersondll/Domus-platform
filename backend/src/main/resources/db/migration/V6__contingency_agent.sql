-- Fase 7: agente local de contingência (identidade, política versionada, eventos idempotentes, conflitos, relé de comandos).
ALTER TABLE device DROP CONSTRAINT device_adapter_check;
ALTER TABLE device ADD CONSTRAINT device_adapter_check
    CHECK (adapter IN ('SIMULATOR', 'GENERIC_HTTP', 'ONVIF', 'AGENT_RELAY'));

CREATE TABLE agent (
    id                   UUID PRIMARY KEY,
    condominium_id       UUID         NOT NULL REFERENCES condominium (id),
    name                 VARCHAR(80)  NOT NULL,
    key_hash             VARCHAR(64)  NOT NULL,
    prev_key_hash        VARCHAR(64),
    prev_key_expires_at  TIMESTAMPTZ,
    status               VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    last_seen_at         TIMESTAMPTZ,
    last_policy_version  BIGINT,
    backlog              INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (condominium_id, name)
);

-- Versão da política de contingência por condomínio; sobe quando credenciais são criadas ou revogadas.
CREATE TABLE condominium_policy_version (
    condominium_id  UUID PRIMARY KEY REFERENCES condominium (id),
    version         BIGINT NOT NULL DEFAULT 1
);

-- Eventos reportados pelo agente. A chave primária é o ID gerado no agente: reenvio = duplicata ignorada.
CREATE TABLE agent_event (
    event_id        UUID PRIMARY KEY,
    agent_id        UUID         NOT NULL REFERENCES agent (id),
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    type            VARCHAR(30)  NOT NULL,
    credential_id   UUID,
    device_id       UUID,
    command_id      UUID,
    entrance        VARCHAR(60),
    reason          VARCHAR(40),
    occurred_at     TIMESTAMPTZ  NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_agent_event_agent ON agent_event (agent_id, received_at DESC);

CREATE TABLE sync_conflict (
    id              UUID PRIMARY KEY,
    event_id        UUID         NOT NULL REFERENCES agent_event (event_id),
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    kind            VARCHAR(30)  NOT NULL CHECK (kind IN
        ('REVOKED_BEFORE_USE', 'QUOTA_EXCEEDED', 'UNKNOWN_CREDENTIAL', 'EXPIRED_AT_USE', 'UNCERTAIN_ENTRY')),
    detail          VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ,
    resolved_by     UUID
);
CREATE INDEX idx_sync_conflict_open ON sync_conflict (condominium_id, created_at) WHERE resolved_at IS NULL;

-- Comandos de abertura destinados ao agente (modelo pull: o agente consulta, não há porta de entrada no condomínio).
CREATE TABLE agent_command (
    id              UUID PRIMARY KEY REFERENCES access_command (id),
    agent_id        UUID         NOT NULL REFERENCES agent (id),
    condominium_id  UUID         NOT NULL REFERENCES condominium (id),
    device_id       UUID         NOT NULL,
    entrance        VARCHAR(60)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    delivered_at    TIMESTAMPTZ
);
CREATE INDEX idx_agent_command_pending ON agent_command (agent_id) WHERE delivered_at IS NULL;
