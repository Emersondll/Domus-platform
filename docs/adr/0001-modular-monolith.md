# ADR 0001 — Monólito modular em vez de microsserviços

**Status:** aceita. **Contexto:** equipe pequena, domínio ainda em descoberta, necessidade de validar localmente.
**Decisão:** um único executável Spring Boot com pacotes por domínio (`identity`, `commercial`, `residents`, …) e
dependências para dentro; módulos se comunicam por contratos (ex.: `CondominiumLookup`). O agente local de
contingência será um executável separado (Fase 7), pois roda em outra máquina.
**Consequências:** menos custo operacional agora; separação futura possível onde houver motivo operacional.
Ciclos entre módulos são proibidos (já houve um, resolvido com `CondominiumLookupAdapter`).
