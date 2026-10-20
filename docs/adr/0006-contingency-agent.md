# ADR 0006 — Agente local de contingência

**Status:** aceita. **Contexto:** os condomínios precisam continuar operando durante quedas de internet, sem que o
agente invente autorizações nem assuma sucesso físico.
**Decisões:** (1) agente em Java separado, modelo **pull** (sem porta de entrada no condomínio); (2) política assinada,
versionada e com validade, com assinatura esperada de cada token (segredo de QR nunca sai da central); (3) contingência
só quando offline; (4) SQLite local com outbox e IDs gerados no agente (idempotência na central); (5) **falha fechada**
quando o uso não pode ser registrado; (6) reconciliação com conflitos explícitos em vez de "última escrita vence";
(7) comandos físicos via relé (`AGENT_RELAY`): "aceito" = enfileirado, confirmação só por evento do agente.
**Alternativas descartadas:** agente com o segredo dos QR Codes (comprometer um agente emitiria credenciais);
sincronização por última escrita (esconderia usos indevidos); abrir porta de entrada no condomínio (superfície de ataque).
**Consequências:** a revogação só vale offline após a expiração da política; o TTL é o parâmetro de risco.
