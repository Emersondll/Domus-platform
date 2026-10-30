-- Ticket numerado de saída: toda entrada concedida (visitante) recebe um número sequencial dentro do condomínio. Na saída, a
-- portaria valida o número e a saída fica registrada. O número só identifica a entrada; não abre porta nenhuma.
-- A numeração é feita no banco (gatilho) para valer para todos os caminhos de entrada: convite, portaria e agente offline.
-- Entradas anteriores a esta migração ficam sem número (a saída delas continua sendo registrada pelo botão da lista).
ALTER TABLE access_attempt ADD COLUMN exit_ticket INTEGER;

CREATE TABLE exit_ticket_counter (
    condominium_id UUID PRIMARY KEY REFERENCES condominium (id),
    last_number    INTEGER NOT NULL
);

CREATE FUNCTION assign_exit_ticket() RETURNS trigger AS $$
DECLARE
    n INTEGER;
BEGIN
    IF NEW.result = 'GRANTED' AND NEW.exit_ticket IS NULL THEN
        INSERT INTO exit_ticket_counter (condominium_id, last_number) VALUES (NEW.condominium_id, 1)
        ON CONFLICT (condominium_id) DO UPDATE SET last_number = exit_ticket_counter.last_number + 1
        RETURNING last_number INTO n;
        NEW.exit_ticket := n;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_assign_exit_ticket BEFORE INSERT ON access_attempt
    FOR EACH ROW EXECUTE FUNCTION assign_exit_ticket();

CREATE UNIQUE INDEX uq_access_attempt_exit_ticket ON access_attempt (condominium_id, exit_ticket)
    WHERE exit_ticket IS NOT NULL;
