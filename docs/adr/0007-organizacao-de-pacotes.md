# ADR 0007 — Organização de pacotes do backend

**Data:** 2026-10-22 · **Status:** aceita

## Contexto
O backend é um monólito modular: um pacote por domínio. Dentro de cada domínio as classes estavam misturadas, e o pacote raiz ainda levava o nome antigo do projeto.

## Decisão
- Pacote raiz: `br.com.domus.platform` (também `br.com.domus.agent` no agente local).
- Cada domínio (`access`, `agent`, `commercial`, `dev`, `devices`, `identity`, `notifications`, `operations`, `parcels`, `privacy`, `reservations`, `residents`, `vehicles`)
  é dividido por tipo de classe:

| Subpacote | Conteúdo |
|---|---|
| `controller` | `*Controller` (borda HTTP; DTOs `record` ficam aninhados no controller) |
| `service` | `*Service` e demais componentes de regra/aplicação (codec de token, migração de PII, assinante de eventos, seed, bootstrap) |
| `repository` | `*Repository` (Spring Data) |
| `entity` | entidades JPA, enums de domínio e conversores de persistência |
| `adapter` | implementações de contratos de integração (ex.: `AgentRelayAdapter`); `devices/adapter` guarda os adaptadores de dispositivo |
| `config` | classes `@Configuration` do domínio |

- `shared/` mantém a própria divisão (`audit`, `config`, `crypto`, `error`, `observability`, `tenant`) e os contratos entre módulos (`CondominiumLookup`, `DeviceHealthLookup`, ...).
- As regras de dependência não mudam: domínio → `shared`; módulos conversam por contratos; **sem ciclos entre domínios**; sem regra de negócio em `controller`.

## Consequências
- Membros que eram visíveis só dentro do pacote e passaram a ser usados entre subpacotes foram tornados `public` (houve um: `AgentService.ONLINE_WINDOW`).
- Os testes de integração continuam em `br.com.domus.platform` (atravessam vários domínios).
- Propriedades de configuração (`portaria.*`), nomes de banco, realm e variáveis de ambiente **não** foram renomeados (identificadores técnicos estáveis).
