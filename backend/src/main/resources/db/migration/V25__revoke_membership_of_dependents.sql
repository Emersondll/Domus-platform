-- Decisão do titular: só adultos usam o app (dependentes ficam no cadastro, sem conta). Vínculos de morador que ainda apontam
-- para um cadastro DEPENDENT são desativados; nada é apagado e o histórico permanece.
UPDATE membership m
   SET active = FALSE
  FROM resident r
 WHERE r.id = m.resident_id
   AND m.role = 'RESIDENT'
   AND m.active
   AND r.resident_type = 'DEPENDENT';
