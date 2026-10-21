# Status do projeto — 2026-10-20

Domus, plataforma de portaria virtual multicondomínio: monólito modular Java (Spring Boot 4.0.7, Java 26), painel da central e app do
morador (React + TypeScript), agente local de contingência (Java + SQLite). **Fases 0–8 executadas.**
**Isto NÃO é "pronto para produção"**: nada foi testado com hardware real e há lacunas listadas abaixo.

## Evidências desta rodada (build limpo, executado de verdade)
| Verificação | Resultado |
|---|---|
| `mvn -f backend/pom.xml clean verify` | **83 testes** (3 unitários + 80 integração com PostgreSQL real), 0 falhas |
| `mvn -f agent/pom.xml clean package` | **16 testes**, 0 falhas; jar executável gerado |
| `cd web && npm test` / `npm run build` | **10** (painel: 5 UI + 5 OIDC/PKCE) + **4** (app do morador); build com checagem de tipos ok |
| `npm audit --omit=dev` (código publicado) | 0 vulnerabilidades |
| `scripts/e2e-contingency.sh` (central e agente reais, `kill -9` da central) | OK: offline autoriza 1×, recusa reuso e token adulterado; na volta uso = 1/1, histórico `OFFLINE`, backlog 0, 0 conflitos |
| `scripts/verify_keycloak.py` (Keycloak 26.4 real) | OK: operador é obrigado a configurar OTP, morador entra direto; PKCE (verifier errado 400, código reutilizado 400); API valida o token; bootstrap do 1º admin; provisionamento por `sub` |
| `scripts/e2e-browser.py` (navegador real) | OK: chamada do morador → operador atende → porta confirmada só por evento; porta sem evento termina "estado desconhecido"; convite/QR, veículo, perfil; sem token em storage; sem erros de console |
| `scripts/restore-drill.sh` | PASS: 16 tabelas, constraints/índices/triggers iguais; `audit_log` segue append-only após restaurar |
| Stack de produção local (Caddy+TLS, Keycloak, app, Postgres) | OK: HTTPS e cabeçalhos, `/actuator` `/dev` `/swagger-ui` `/v3/api-docs` = 404 no proxy, porta de gestão inacessível de fora, token do Keycloak (issuer HTTPS) aceito, token adulterado 401 |
| Prometheus + Grafana | alvo `up`, 9 regras de alerta sem erro, dashboard provisionado (12 painéis) |
| `scripts/load-test.py` (mesma máquina, piso) | leitura ~3.000–3.700 req/s; escrita ~1.900–2.300 req/s; p95 16–54 ms; 0 erros; 0 timeouts do pool |
| CI (`.github/workflows/ci.yml`) | **não executado** (só validado como YAML e `docker compose config`) |

## Concluído e verificado
- **Fundação:** multi-tenant (empresa → condomínio → bloco → unidade → morador) com 404 entre tenants, FKs compostas, papéis por
  associação, auditoria append-only; OpenAPI; erros RFC 9457; paginação.
- **Gestão condominial:** veículos, visitantes, encomendas, áreas comuns e reservas (sem sobreposição garantida pelo banco), convites com
  QR (HMAC amarrado ao condomínio, sem dado pessoal, uso único por UPDATE atômico), tentativas registradas.
- **Central de operações:** turnos, fila com atribuição atômica (`SKIP LOCKED`), transferência, justificativa obrigatória em
  entrada liberada/negada, histórico, incidentes, visão geral com saúde de dispositivos e conectividade reais.
- **App do morador (PWA)** e **painel da central:** fluxos principais, login OIDC (Authorization Code + PKCE), estados honestos
  (pendências marcadas, "porta aberta" só com confirmação).
- **Dispositivos:** contrato `DeviceAdapter`, adaptadores `SIMULATOR`/`GENERIC_HTTP`/`ONVIF`/`DAHUA_CGI`/`AGENT_RELAY`, comandos físicos com
  estados verificáveis, idempotência, ingestão de eventos por chave do dispositivo, anti-SSRF e anti-XXE (ADR 0005).
- **Contingência (agente local):** identidade e chave rotacionável, política assinada/versionada/com validade, contingência só
  offline, fila SQLite, falha fechada, sincronização idempotente, reconciliação com conflitos auditáveis; os 10 cenários de falha exigidos
  têm teste (`docs/agent.md`, ADR 0006).
- **Identidade:** Keycloak com MFA (TOTP) obrigatório para papéis de equipe, bloqueio por tentativas, política de senha, clientes PKCE;
  API de provisionamento (admin de empresa → operadores/síndicos → contas de morador); bootstrap do primeiro administrador.
- **Operação:** métricas Prometheus + métricas de negócio, tracing OpenTelemetry (exportação desligada por padrão), logs JSON,
  limitação de taxa e bloqueio por força bruta em credenciais de máquina, retenção/expurgo, exportação e anonimização do titular,
  backup/restauração com drill, stack de produção separada, documentação (`operations`, `security`, `lgpd`).
- **Achados reais corrigidos por teste/carga:** dependência circular entre módulos; **deadlock do pool de conexões na auditoria
  (`REQUIRES_NEW`)** achado no teste de carga (`GET moradores` caía de 3.000 req/s para 1 req/s com timeouts de 30 s) e fixado com teste de
  regressão (`PoolStarvationIT`); métricas de negócio que nunca atualizavam (`ResultSet` sem `next()`); healthcheck do contêiner; testes que
  "passavam" sem rodar (failsafe).

## Simulado (somente desenvolvimento/teste)
- Login `/dev/token` e seed fictício (`DevSeed`, perfil `dev`; não existe em produção nem nos bundles de produção).
- Dispositivos `SIMULATOR`; controladora do agente (`SimulatedDoor`); central falsa nos testes do agente (contrato idêntico ao da API real,
  que tem testes próprios); `GENERIC_HTTP`, `ONVIF` e `DAHUA_CGI` só validados contra servidores HTTP locais de teste.

- **Achado do E2E:** a tela de perfil sobrescrevia o que a pessoa digitava quando a carga do perfil (ou, agora, a renovação do token) chegava depois; corrigido e coberto por teste.
- **Audiência do token:** a API recusa token sem `aud=portaria-api` (`OIDC_AUDIENCE`, padrão em produção; o realm tem o mapper nos dois clientes).
  Provado por `scripts/verify_keycloak.py` (token de outro cliente → 401; token do fluxo real do front → aceito).

## Pendente / não feito (em ordem de impacto)
1. **Hardware real:** nenhum adaptador testado com equipamento (controladoras, câmeras, interfones); o primeiro fabricante (`DAHUA_CGI`, família Dahua/Intelbras, Digest)
   tem comando `openDoor` e assinatura de eventos (`eventManager.cgi`, reconexão com espera, detecção de fluxo mudo). 17 testes (14 unitários e 3 de integração)
   passam contra um equipamento falso que valida o Digest de verdade, inclusive ponta a ponta com o banco (confirma só se o evento devolver o
   `UserID=portaria-<commandId>`; senão `UNKNOWN`). **A documentação oficial não foi obtida** (NDA/solicitação ao fabricante): as fontes são
   públicas e não oficiais e várias suposições (campos `Status`/`UserID`, `heartbeat`) estão **não confirmadas**; ver
   `docs/reference-analysis/DAHUA_SOURCES.md` e o roteiro de campo. Falta teste em equipamento, teste com perda de conectividade durante o comando e
   eleição de líder entre instâncias (cada instância abre sua própria conexão de eventos).
2. **SIP/telefonia, vídeo ao vivo (RTSP/`GetStreamUri`), descoberta de dispositivos** — não implementados.
3. **Notificações push, e-mail e WhatsApp (API oficial)** — hoje só caixa de entrada in-app.
4. **Navegador:** `scripts/e2e-browser.py` (Chromium/Playwright, login de desenvolvimento) cobre os fluxos do morador e do operador; falta exercitar o login
   OIDC/PKCE e a renovação silenciosa do token **num navegador contra o Keycloak real** (o PKCE e a API já são provados por `verify_keycloak.py`; a renovação tem 9 testes unitários).
5. **mTLS** por agente/dispositivo; criptografia de campos pessoais e do SQLite do agente;
   WAF/DDoS; pentest independente; varredura de imagens.
6. **Alta disponibilidade/escala horizontal:** limitador de taxa e agendamentos são por instância (precisam de Redis/lock distribuído);
   PostgreSQL sem réplica/PITR; RTO/RPO não medidos em produção.
7. **LGPD:** decisões jurídicas/operacionais (base legal, DPO, RIPD, contratos controlador/operador, retenção final) — ver `docs/lgpd.md`;
   política para dados de visitantes; retenção/acesso a gravações quando houver vídeo.
8. **Atualização segura do agente** (binário assinado + verificação) e instalador; hoje manual.
9. Operador-chefe/relatórios por condomínio e exportação de relatórios (só há listagens e auditoria); cadastro de planos/contratos/limites.
10. App nativo (React Native) se houver necessidade de push nativo, NFC ou lojas (ADR 0004).
11. CI: rodou no GitHub e acusou 2 falhas (teste dependente de ordem e versão inexistente do osv-scanner), corrigidas localmente; falta confirmar numa nova execução. Licença do projeto: MIT (`LICENSE`); skills do agente ficam só na máquina local (`.claude/` no `.gitignore`).

## Riscos aceitos (conscientes)
- **Revogação durante a queda** só chega ao agente quando a política renova (TTL 6 h): janela limitada e auditável (`REVOKED_BEFORE_USE`).
- Quota offline é por agente (credencial multiuso pode estourar com dois agentes offline; vira `QUOTA_EXCEEDED` na volta).
- Front-end exige **Node ≥ 22.12** (`vitest 5`). `npm audit` (inclui devtools) = 0 vulnerabilidades desde a atualização para
  `vitest 5.0.5` e `@vitejs/plugin-react 4.7.0`; 14 testes e os dois builds passam no Node 22.23.
- Grafana OSS é AGPL-3.0: usado como serviço separado e sem modificação; revisar com jurídico se oferecido a terceiros.

## Decisões minhas (reversíveis)
Java 26 (instalado; não é LTS — considerar 25 LTS) · monólito modular em vez de microsserviços (ADR 0001) · app do morador como PWA e não
Flutter/React Native (ADR 0004) · agente em modelo pull · TTL da política 6 h · TOTP por papel via fluxo condicional do Keycloak ·
auditoria de sucesso na transação da ação · sem retry automático de comando físico · porta de gestão separada (8082) · `AGENT_RELAY`.

## Como validar localmente
`README.md` traz os comandos. Resumo: `docker compose up -d postgres` → API (`SPRING_PROFILES_ACTIVE=dev`) → `npm run dev -w panel` /
`-w resident`; observabilidade e Keycloak por `docker-compose.observability.yml` e `docker-compose.keycloak-dev.yml`.
