-- Imagem da câmera de entrada enviada junto do pedido de autorização de visita. Conteúdo cifrado em nível de campo
-- (JPEG em base64, AES-GCM); sem URL pública. Sem pedido vinculado, a imagem é temporária (expires_at) e depois é apagada;
-- vinculada, segue o prazo de retenção da imagem (portaria.snapshots.retention-days).
CREATE TABLE visit_snapshot (
    id                    UUID PRIMARY KEY,
    condominium_id        UUID        NOT NULL,
    device_id             UUID        NOT NULL,
    captured_by_user_id   UUID        NOT NULL REFERENCES app_user (id),
    captured_at           TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    visit_request_id      UUID,
    content               TEXT        NOT NULL,
    FOREIGN KEY (device_id, condominium_id) REFERENCES device (id, condominium_id),
    FOREIGN KEY (visit_request_id, condominium_id) REFERENCES visit_request (id, condominium_id)
);
CREATE UNIQUE INDEX uq_visit_snapshot_request ON visit_snapshot (visit_request_id) WHERE visit_request_id IS NOT NULL;
CREATE INDEX idx_visit_snapshot_expiry ON visit_snapshot (expires_at) WHERE visit_request_id IS NULL;
CREATE INDEX idx_visit_snapshot_captured ON visit_snapshot (captured_at);
