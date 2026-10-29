# Status do projeto — 2026-10-26

Domus, plataforma de portaria virtual multicondomínio: monólito modular Java (Spring Boot 4.1.2, Java 26), painel da central e app do
morador (React + TypeScript), agente local de contingência (Java + SQLite). **Fases 0–8 executadas.**
**Isto NÃO é "pronto para produção"**: nada foi testado com hardware real e há lacunas listadas abaixo.

> Painel de acompanhamento de bugs, pendências e decisões em aberto: [`pendencias.md`](../pendencias.md).

## Evidências desta rodada (build limpo, executado de verdade)
| Verificação | Resultado |
|---|---|
| `mvn -f backend/pom.xml verify` | **Rodada de 2026-10-29: exit 0, 0 falhas** (inclui `InvitationLimitIT` e o limite de 5 aberturas/min). Contagem anterior: **220 testes** (41 unitários + 179 integração com PostgreSQL real), 0 falhas (2026-10-28, após a validação do fluxo do administrador; testes organizados por domínio) |
| `mvn -f agent/pom.xml verify` | **43 testes**, 0 falhas; jar executável gerado |
| `scripts/e2e-agent-update.sh` + `scripts/test-agent-launcher.sh` (reexecutados em 2026-10-27; o passo do reinício foi corrigido para parar o agente com TERM como o systemd, sem `timeout` sobre o launcher, e sem deixar java órfão) | OK: jars reais 1.0.0 → 1.1.0 assinada, reinício, confirmação; manifesto adulterado e de outra chave recusados; rollback do launcher (3 falhas) |
| `cd web && npm test` / `npm run build` | **67** (painel) + **37** (app do morador); build com checagem de tipos ok, código de saída 0 (2026-10-29) |
| `npm audit --omit=dev` (código publicado) | 0 vulnerabilidades |
| `scripts/e2e-contingency.sh` (**apaga o banco de desenvolvimento**: roda `docker compose down -v`) | (central e agente reais, `kill -9` da central) | OK: offline autoriza 1×, recusa reuso e token adulterado; na volta uso = 1/1, histórico `OFFLINE`, backlog 0, 0 conflitos |
| `scripts/verify_keycloak.py` (Keycloak 26.8.1 real, reexecutado em 2026-10-28) | OK: operador é obrigado a configurar OTP, morador entra direto; PKCE (verifier errado 400, código reutilizado 400); API valida o token; bootstrap do 1º admin; provisionamento por `sub` |
| `scripts/e2e-browser.py` (navegador real) | OK (54 verificações, 2026-10-26; inclui o administrador da empresa criando condomínio e cadastrando agente pela tela; inclui o painel como síndico (cadastra, libera, retira e desativa câmera pela tela); inclui o monitoramento de câmeras ao lado da mesa do condomínio e o morador vendo ao vivo as câmeras de entrada liberadas pelo síndico (47 verificações em 2026-10-26); inclui "Meus acessos" (entrada e saída de um morador cadastrado por leitores simulados, referência desconhecida sem registro), a foto de cadastro sintética com consentimento e revogação que apaga, e o "Último acesso" no início; inclui a imagem da câmera de entrada indo junto do aviso até o morador e a captura de quadro por MediaMTX + ffmpeg; inclui entrada e saída de prestador autorizado pelo morador, a janela de alerta de pânico na central com a unidade de origem; inclui cadastro de cônjuge e animal pelo morador visto na portaria, conversa morador ↔ portaria, validação de convite pela tela e câmeras, com o player de vídeo ao vivo chegando a "Ao vivo" via MediaMTX real): chamada do morador → operador atende → porta confirmada só por evento; porta sem evento termina "estado desconhecido"; convite/QR, veículo, perfil; autorização de visitante e pânico; encomendas; comunicado, livro de turno, saída de visitante, relatório/CSV e abertura remota pelo morador; sem token em storage; sem erros de console |
| `scripts/api-smoke.py` (API em execução) | OK (2026-10-27; **achou um vazamento real** em `me/accesses/policy`, que respondia 200 a outro condomínio, corrigido): 55 GETs de condomínio × 4 papéis; antes, em 2026-10-26: 148 endpoints mapeados; 45 GETs de condomínio × 4 papéis sem 5xx, sem vazamento entre empresas e sem acesso sem token. Matriz em `docs/endpoint-coverage.md` |
| `scripts/e2e-agent-video.py` (MediaMTX + ffmpeg reais, agente real, 127.0.0.1) | **OK (2026-10-28):** câmera `videoSource=AGENT`; o agente publica, a playlist HLS responde 200, sem token 401, o ffmpeg morto volta sozinho, agente parado = vídeo some. Não é câmera real nem internet real |
| `scripts/e2e-browser.py` (com vídeo, 2026-10-29, jar final) | OK, **72 verificações** (exit 0). Novas: cadastro de bloco, unidade e morador, acesso ao app, exportar, desativar/reativar, anonimizar, áreas comuns, reserva e cancelamento pela equipe, incidente, revogar convite, remover familiar e animal, preferência de aviso, animal e relatórios pelo painel, empresa e administrador pela plataforma, e a recusa de equipe pelo síndico. Rotas tocadas pelas telas: 88 → 121 de 161 (`E2E_TRACE_REQUESTS`) |
| `scripts/load-test.py` (2026-10-29, após trava de convites e limites novos, 8 cenários) | 0 erros: leitura 816–1.247 req/s (p95 26–42 ms), validar credencial 1.886 req/s, evento de leitor com identidade 299 req/s, "Meus acessos" 280 req/s, sessão de vídeo 268 req/s, validação do token de vídeo 5.252 req/s |
| `scripts/load-test.py` (2026-10-28, banco de desenvolvimento cheio, 8 cenários) | 0 erros. **Achou um deadlock meu** na abertura de sessões de vídeo (11 req/s, 12 erros) e a ultrapassagem do teto; corrigido com lock por usuário (241 req/s, 0 erros). Validação do token de vídeo: 3.871 req/s. "Meus acessos" 319 req/s nesse banco (200 mil linhas de auditoria); em banco limpo e A/B com o jar anterior deu ~1.100 req/s dos dois lados |
| `scripts/e2e-two-instances.py` (duas JVMs reais, equipamento Dahua falso) | **OK (2026-10-27, 2 execuções):** só 1 conexão de eventos aberta; SIGKILL; takeover pela posse. Achou o B27 (fluxo reconectando a cada ~6 s). Não é prova com equipamento real |
| `scripts/e2e-oidc-browser.py` (Chromium real + Keycloak de desenvolvimento, API sem perfil dev) | **OK (15 verificações, 2026-10-27)**: o morador entra pelo formulário do Keycloak e a API aceita o token; o token curto (30 s) é **renovado em segundo plano** (`refresh_token`); recarregar e tocar em Entrar volta sem pedir senha (SSO); o operador é obrigado a **cadastrar o autenticador TOTP** (o código é calculado no teste, RFC 6238), depois o login seguinte pede o OTP e um OTP errado é recusado; **"Sair" encerra a sessão no Keycloak** (corrigido: sem `id_token_hint` o Keycloak só mostrava uma tela de confirmação); nenhum token em storage; nenhuma resposta 401/403. Limite: só Chromium, sem celular e sem Safari |
| `scripts/restore-drill.sh` | PASS (24 migrações, V1–V24; 2026-10-28): tabelas, constraints/índices/triggers iguais; `audit_log` segue append-only após restaurar |
| Stack de produção local (Caddy+TLS, Keycloak, app, Postgres) | OK: HTTPS e cabeçalhos, `/actuator` `/dev` `/swagger-ui` `/v3/api-docs` = 404 no proxy, porta de gestão inacessível de fora, token do Keycloak (issuer HTTPS) aceito, token adulterado 401 |
| Prometheus + Grafana | alvo `up`, 9 regras de alerta sem erro, dashboard provisionado (12 painéis) |
| `scripts/load-test.py` (2026-10-26, mesma máquina, piso, 20 conexões) | **reexecutado após "Meus acessos"**: leitura 1.100–1.250 req/s (p95 29–34 ms), validar credencial 870 req/s, **evento de leitor com identidade 280 req/s (p95 206 ms)**, **consulta de "Meus acessos" auditada 1.550 req/s (p95 22 ms)**, 0 erros. O evento de leitor é o mais lento porque o teste usa **um único dispositivo** (cada evento atualiza a linha dele: contenção que leitores diferentes não têm); sem limite de taxa de dispositivo elevado (`PORTARIA_RATE_LIMIT_DEVICE_PER_MINUTE`) o limitador responde 429, como deve. Medição anterior: | após a criptografia de campos: leitura ~900–1.500 req/s (inclusive `GET condomínios`, que não tem dado pessoal), escrita ~1.600 req/s; p95 21–36 ms; 0 erros; 0 timeouts. **A/B/A/B/A/B contra o jar anterior à cifra (2026-10-22): sem diferença além do ruído (±40% entre rodadas do mesmo jar); cifra ≈2 µs/op. A queda de 3.000 req/s vinha do ambiente. Dimensionar produção em hardware dedicado** |
| CI (`.github/workflows/ci.yml`) | **verde em 2026-10-22 (7 jobs) e nos pushes seguintes até `5f18010` (2026-10-27); `66f4fca` falhou só no gitleaks (senha de teste fixa em `e2e-oidc-browser.py`, já trocada por uma aleatória)**: backend, agente, web, compose, CodeQL (Java 26 e JS/TS), OSV (via SBOM CycloneDX: 417 pacotes, sem achados) e gitleaks. A rodada achou e corrigiu vulnerabilidades reais de dependências (Boot 4.0.9, Jackson 3.1.7/2.21.7, OpenTelemetry 1.62.0, assertj 3.27.7) |

## Concluído e verificado
- **Fundação:** multi-tenant (empresa → condomínio → bloco → unidade → morador) com 404 entre tenants, FKs compostas, papéis por
  associação, auditoria append-only; OpenAPI; erros RFC 9457; paginação.
- **Gestão condominial:** veículos, visitantes, encomendas, áreas comuns e reservas (sem sobreposição garantida pelo banco), convites com
  QR (HMAC amarrado ao condomínio, sem dado pessoal, uso único por UPDATE atômico), tentativas registradas.
- **Central de operações:** turnos, fila com atribuição atômica (`SKIP LOCKED`), transferência, justificativa obrigatória em
  entrada liberada/negada, histórico, incidentes, visão geral com saúde de dispositivos e conectividade reais.
- **App do morador (PWA)** e **painel da central:** fluxos principais, login OIDC (Authorization Code + PKCE), estados honestos
  (pendências marcadas, "porta aberta" só com confirmação).
- **Relatórios (2026-10-27):** além do relatório de acessos, o resumo da atividade do período (encomendas, atendimentos, ocorrências, livro de turno; só números; JSON e CSV; `GET /condominiums/{id}/reports/activity[.csv]`, até 31 dias). Busca e ordenação: placa por igualdade e ordenada em memória, e-mail único e buscável por igualdade, índice cego reconstruído se a chave mudar, posse por dispositivo da conexão de eventos Dahua (V20, V21; `docs/operations.md`).
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

- **Dados pessoais cifrados (AES-256-GCM em nível de campo):** nome, e-mail, telefone, nome do visitante/chamador, placa; placa única por índice cego; chaves rotacionáveis;
  migração de dados legados (`FieldCipherTest`, `FieldEncryptionIT`, drill de restauração, E2E). Armazenamento do agente cifrado em repouso (política, fila, chave de API),
  recusa chave errada sem apagar nada (`StoreEncryptionTest`).
- **Atualização segura do agente:** manifesto assinado (Ed25519), anti-retrocesso, hash/tamanho, instalação atômica e rollback pelo launcher (`UpdateTest`, scripts acima).
- **Audiência do token** (`aud=portaria-api`) e **renovação silenciosa do token** nos apps.
- **Tema Domus** nos dois apps e no login do Keycloak.

- **"Meus acessos" do morador (2026-10-26):** identidade de acesso (cartão, usuário do terminal, QR, app; referência cifrada e índice cego), registro das passagens só de
  pessoas cadastradas, sentido pelo ponto de acesso (sem sentido = "passagem"), tela do morador com filtro de período, "Último acesso" no início, consulta auditada,
  retenção de 90 dias (padrão), exportação e anonimização incluem o histórico e a foto. **Foto de cadastro com consentimento específico, versionado e revogável (apaga
  na hora), cifrada, só o titular a vê; recurso desligado por padrão (`RESIDENT_PHOTO_ENABLED`).** Provado com leitores simulados e imagens sintéticas
  (`ResidentAccessIT`, `ResidentPhotoIT`, E2E). **Sem equipamento real** os campos do evento Dahua (`CardNo`/`UserID`) seguem suposição (B6). Plano e limites: `docs/plano-meus-acessos.md`.

## Simulado (somente desenvolvimento/teste)
- Login `/dev/token` e seed fictício (`DevSeed`, perfil `dev`; não existe em produção nem nos bundles de produção).
- Dispositivos `SIMULATOR`; controladora do agente (`SimulatedDoor`); central falsa nos testes do agente (contrato idêntico ao da API real,
  que tem testes próprios); `GENERIC_HTTP`, `ONVIF` e `DAHUA_CGI` só validados contra servidores HTTP locais de teste.

- **Achado do E2E:** a tela de perfil sobrescrevia o que a pessoa digitava quando a carga do perfil (ou, agora, a renovação do token) chegava depois; corrigido e coberto por teste.
- **Audiência do token:** a API recusa token sem `aud=portaria-api` (`OIDC_AUDIENCE`, padrão em produção; o realm tem o mapper nos dois clientes).
  Provado por `scripts/verify_keycloak.py` (token de outro cliente → 401; token do fluxo real do front → aceito).

## Pendente / não feito (em ordem de impacto)
1. **Hardware real:** nenhum adaptador testado com equipamento; `DAHUA_CGI` (Dahua/Intelbras, Digest + `eventManager.cgi`) passa em 17 testes contra um equipamento falso, mas a
   **documentação oficial não foi obtida** (NDA) e várias suposições estão não confirmadas (`docs/reference-analysis/DAHUA_SOURCES.md`, com roteiro de campo). Falta teste com perda de
   conectividade durante o comando e eleição de líder entre instâncias.
2. **SIP/telefonia, `GetStreamUri` ONVIF, descoberta de dispositivos** — não implementados. **Vídeo ao vivo (RTSP → HLS, MediaMTX)** existe e foi provado só com fonte RTSP de teste (`docs/cameras.md`): falta câmera real e o agente local publicar o fluxo de câmeras atrás de NAT.
3. **Notificações push, e-mail e WhatsApp (API oficial)** — hoje só caixa de entrada in-app.
4. **Navegador:** o login OIDC/PKCE e a renovação do token foram exercitados em Chromium contra o Keycloak real (`e2e-oidc-browser.py`). Falta o mesmo em **celular real e Safari/iPhone** (B17).
5. **Segurança restante:** mTLS por agente/dispositivo; WAF/DDoS; pentest independente; varredura de imagens de contêiner; criptografia de armazenamento do banco/disco (a de campos já existe);
   rotação da chave de lançamento do agente; chaves de criptografia em gerenciador de segredos/KMS (hoje variável de ambiente).
6. **Alta disponibilidade/escala horizontal:** limitador de taxa e agendamentos por instância (Redis/lock distribuído); a conexão de eventos Dahua já tem posse por dispositivo (B7), mas expiração de comandos, retenção, métricas e o limitador de taxa seguem por instância; PostgreSQL sem réplica/PITR; RTO/RPO não medidos.
7. **LGPD:** decisões jurídicas/operacionais (base legal, DPO, RIPD, contratos, retenção final) — `docs/lgpd.md`; política para dados de visitantes; gravações quando houver vídeo.
8. **Administração:** as telas do síndico e da portaria existem (aba Cadastros); as da plataforma e da empresa operadora também (botão "Administração": empresas e administradores, condomínios, agentes, conflitos de sincronização; "Girar chave" na aba Portas), com as regras de papel que o servidor já impõe (D8 sem resposta formal); **faltam** listar/revogar administradores, editar/suspender empresa ou condomínio. Relatório de acessos com CSV existe; faltam os de encomendas, ocorrências e turnos. Planos, contratos e limites: decisão D7.
9. Instalador/pacote (.deb) do agente e suporte a outros sistemas além de Linux/systemd.
10. App nativo (React Native) se houver necessidade de push nativo, NFC ou lojas (ADR 0004).
11. **CI:** validado em 2026-10-22. Licença Apache 2.0 (`LICENSE`); `.claude/` e `CLAUDE.md` ficam só na máquina local e fora do histórico.

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
