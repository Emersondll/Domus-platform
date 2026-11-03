# Retenção e descarte (rascunho técnico — prazos são decisão do controlador e do jurídico)

## O que o sistema faz hoje
Um expurgo diário (03:30) apaga o que passou do prazo. Prazos configuráveis em `application.yml` (bloco `portaria.retention`).

| O quê | Prazo hoje | Observação |
|---|---|---|
| Notificações | 90 dias | |
| Tentativas de acesso | 365 dias | registros ainda referenciados são preservados |
| Eventos de agente e de dispositivo | 90 dias | |
| Conflitos de sincronização resolvidos | 365 dias | |
| Histórico pessoal de entradas e saídas do morador | 90 dias (`RESIDENT_ACCESS_RETENTION_DAYS`) | D19: a confirmar |
| Imagem da câmera de visita (recurso desligado) | 10 min não enviada / 30 dias enviada | D12/D13 |
| Mensagens morador ↔ portaria | **não expurga** (`DIRECT_MESSAGE_RETENTION_DAYS=0`) | decisão do controlador |
| Eventos de login do Keycloak (com IP) | 30 dias | `eventsExpiration` do realm |
| Backups locais | 14 cópias | cópia de fora: a definir |

## O que NUNCA expira hoje (precisa de decisão)
- **`audit_log`**: somente-inserção por trigger. Guarda quem fez o quê e quando (sem texto de mensagem, sem imagem, **sem IP**). Descartar exige procedimento
  controlado de DBA, em transação auditada. *Pergunta ao advogado:* por quanto tempo a trilha de auditoria pode/deve ser mantida e com qual justificativa?
- Visitantes (`visitor`, `visit_request`), chamadas (`service_call`) e livro de turno (`shift_log_entry`): sem prazo de expurgo.
- Cadastros de moradores: ficam até o morador ser **anonimizado** (direito de eliminação já implementado: `POST …/residents/{id}/anonymize`), mas **não** há descarte automático
  de quem se mudou e foi apenas desativado.

## Eliminação a pedido do titular
Implementada para o morador: exportar (portabilidade/acesso) e anonimizar. **Limites:** a anonimização não altera `audit_log` nem os registros de segurança
(não contêm o nome do morador); dados em **backups** só saem na rotação; o visitante não tem como pedir (não há cadastro próprio dele).

## Decisões a tomar
1. Prazo para cada linha da tabela acima e a justificativa de cada um.
2. Política para o `audit_log` (guardar por quanto tempo; quem aprova o descarte).
3. Dados de visitantes: prazo e como atender o pedido de um visitante.
4. Backups: por quanto tempo guardar e onde (e se a cópia de fora fica no Brasil, D6).
5. Endereço IP: hoje só o Keycloak o guarda (30 dias). Guardar IP também na auditoria (T11) **só se o jurídico aprovar**, com prazo de descarte próprio.
