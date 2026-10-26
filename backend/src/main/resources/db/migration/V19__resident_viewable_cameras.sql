-- Câmeras de entrada que o morador pode ver ao vivo (o síndico escolhe; no máximo 4 por condomínio, regra do serviço).
ALTER TABLE device ADD COLUMN resident_viewable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE device ADD CONSTRAINT device_resident_viewable_camera CHECK (NOT resident_viewable OR kind = 'CAMERA');
