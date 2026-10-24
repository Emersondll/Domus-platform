-- Lote A: autorização de visitante pelo morador (sem convite prévio) e alerta de pânico.

-- Pedido da portaria ao morador. O nome do visitante é dado pessoal e fica cifrado na aplicação (TEXT).
-- A expiração é calculada na leitura (PENDING vencido aparece como EXPIRED); só a decisão é gravada.
CREATE TABLE visit_request (
    id                    UUID PRIMARY KEY,
    condominium_id        UUID        NOT NULL,
    unit_id               UUID        NOT NULL,
    call_id               UUID,
    visitor_name          TEXT        NOT NULL,
    kind                  VARCHAR(16) NOT NULL CHECK (kind IN ('VISITOR', 'SERVICE_PROVIDER')),
    status                VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'DENIED')),
    requested_by_user_id  UUID        NOT NULL REFERENCES app_user (id),
    decided_by_user_id    UUID REFERENCES app_user (id),
    created_at            TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    decided_at            TIMESTAMPTZ,
    FOREIGN KEY (unit_id, condominium_id) REFERENCES unit (id, condominium_id),
    CHECK ((status = 'PENDING') = (decided_at IS NULL AND decided_by_user_id IS NULL))
);
CREATE INDEX idx_visit_request_unit ON visit_request (condominium_id, unit_id, created_at DESC);
CREATE INDEX idx_visit_request_call ON visit_request (condominium_id, call_id);

-- Pânico entra na mesma fila do atendimento, com prioridade (ver ServiceCallRepository.claimNext).
ALTER TABLE service_call DROP CONSTRAINT service_call_source_check;
ALTER TABLE service_call ADD CONSTRAINT service_call_source_check
    CHECK (source IN ('MANUAL', 'RESIDENT_APP', 'INTERCOM', 'PANIC'));
