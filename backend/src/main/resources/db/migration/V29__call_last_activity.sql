-- Último sinal de vida do operador numa chamada em atendimento (assumir, transferir, sinal periódico do painel).
-- Serve para devolver sozinha à fila a chamada de um operador que sumiu (navegador fechado, rede caída).
ALTER TABLE service_call ADD COLUMN last_activity_at TIMESTAMPTZ;
UPDATE service_call SET last_activity_at = COALESCE(assigned_at, created_at) WHERE status = 'ASSIGNED';
CREATE INDEX idx_service_call_assigned_activity ON service_call (last_activity_at) WHERE status = 'ASSIGNED';
