-- Prazo opcional do comunicado: depois dele o morador deixa de vê-lo (o síndico e a portaria continuam vendo o histórico).
ALTER TABLE announcement ADD COLUMN expires_at TIMESTAMPTZ;
