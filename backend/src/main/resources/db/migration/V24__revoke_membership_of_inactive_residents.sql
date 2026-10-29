-- Antes desta versão, desativar um morador não encerrava o vínculo de acesso dele (chamava a central, convidava e via câmeras).
-- A regra nova revoga o vínculo na própria desativação; aqui ajustamos o que já ficou para trás. Só desativa vínculos
-- de morador cujo cadastro está inativo; nada é apagado e o histórico permanece. Reativar exige emitir a conta de novo.
UPDATE membership m
   SET active = FALSE
  FROM resident r
 WHERE r.id = m.resident_id
   AND m.role = 'RESIDENT'
   AND m.active
   AND NOT r.active;
