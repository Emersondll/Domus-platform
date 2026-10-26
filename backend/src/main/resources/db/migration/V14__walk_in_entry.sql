-- Entrada de visitante/prestador autorizado pelo morador na portaria (sem convite): a portaria registra a entrada
-- ligada ao pedido aprovado, para ele contar como "no local" e poder ter a saída registrada.
ALTER TABLE visit_request ADD CONSTRAINT uq_visit_request_condominium UNIQUE (id, condominium_id);
ALTER TABLE access_attempt ADD COLUMN visit_request_id UUID;
ALTER TABLE access_attempt ADD CONSTRAINT fk_attempt_visit_request
    FOREIGN KEY (visit_request_id, condominium_id) REFERENCES visit_request (id, condominium_id);
-- um pedido aprovado gera no máximo uma entrada
CREATE UNIQUE INDEX uq_attempt_visit_request ON access_attempt (visit_request_id) WHERE visit_request_id IS NOT NULL;
