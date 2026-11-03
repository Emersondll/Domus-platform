# Contrato condomínio ↔ empresa de portaria ↔ plataforma — checklist (rascunho)

> Lista do que o contrato (ou aditivo) deve tratar do ponto de vista técnico/operacional. Redação e cláusulas são do advogado.

1. **Papéis** de cada parte (controlador, operador, suboperador) e a instrução documentada de tratamento (art. 39 — para conferência).
2. **Objeto e finalidade** do tratamento: o que o sistema faz (ver `inventario-de-dados.md`) e o que **não** faz (sem gravação de vídeo, sem biometria, sem uso para marketing).
3. **Isolamento entre condomínios:** o sistema impede que um condomínio veja outro (testado); o administrador da plataforma **não** lê dados de moradores.
4. **Subprocessadores:** hospedagem (D6), e-mail/WhatsApp (se algum dia houver) e qualquer outro; autorização prévia e lista pública.
5. **Segurança:** o que `docs/security.md` descreve (cifragem de campos, MFA da equipe, auditoria, backups) e o que **não** está coberto (pentest independente, WAF, réplica do banco) — não prometer além disso.
6. **Direitos dos titulares:** quem recebe o pedido, prazo e como o sistema ajuda (exportação, anonimização).
7. **Incidentes:** prazo e canal para o operador avisar o controlador; quem notifica a ANPD e os titulares.
8. **Retenção e devolução/eliminação** ao fim do contrato (inclusive backups).
9. **Auditoria:** direito do controlador de ver a trilha de ações do seu condomínio.
10. **Responsabilidade por equipamentos e câmeras** instalados pelo condomínio (aviso no local, D21).
11. **Transferência internacional**, se o servidor ficar fora do Brasil (D6).
