# ADR 0002 — Estratégia multi-tenant

**Status:** aceita. **Modelo:** Empresa operadora → Condomínio → Bloco → Unidade → Morador.
**Decisão:** banco único, coluna `condominium_id` em toda tabela condominial, com FKs compostas
`(block_id, condominium_id)` e `(unit_id, condominium_id)` para o banco recusar vínculos entre condomínios.
O tenant é derivado de `membership` do usuário autenticado; a URL só informa o alvo, que é validado.
Recurso fora do escopo responde 404 (sem revelar existência); permissão insuficiente com acesso parcial responde 403.
Operador multicondomínio = uma `membership` OPERATOR por condomínio (política explícita).
`PLATFORM_ADMIN` gere empresas e não lê dados de moradores.
**Em aberto:** Row-Level Security no PostgreSQL como segunda camada (avaliar na Fase 8).
