-- B7: com várias instâncias da API, só uma mantém a conexão de eventos de cada controladora. A instância dona renova a posse
-- a cada ciclo de reconciliação; se ela cair, a posse vence sozinha e outra assume. Usa o relógio do banco (sem depender de
-- hora sincronizada entre instâncias) e nenhuma conexão fica presa ao banco.
CREATE TABLE device_event_lease (
    device_id   UUID PRIMARY KEY REFERENCES device (id) ON DELETE CASCADE,
    owner       VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);
