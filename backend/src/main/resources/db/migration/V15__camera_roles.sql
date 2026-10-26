-- Câmeras: desativar sem apagar e marcar a câmera de entrada de visitas (no máximo uma por entrada).
ALTER TABLE device ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE device ADD COLUMN visitor_entrance BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device ADD CONSTRAINT device_visitor_entrance_camera CHECK (NOT visitor_entrance OR kind = 'CAMERA');
CREATE UNIQUE INDEX uq_device_visitor_entrance ON device (condominium_id, entrance) WHERE visitor_entrance;
