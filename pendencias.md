# Pendências, bugs, melhorias e decisões em aberto

Atualizado em 2026-10-28. Fonte de verdade técnica detalhada: [`docs/STATUS.md`](docs/STATUS.md). Este arquivo é o **painel de acompanhamento**:
o que falta, o que está arriscado e o que depende de uma decisão de quem é titular do produto.

> Estado: **não está pronto para produção.** Nenhum equipamento real foi testado, e há decisões jurídicas e de infraestrutura em aberto.

## 1. Evidência do estado atual (2026-10-28)

| Verificação | Resultado |
|---|---|
| Backend `mvn verify` | 184 testes (33 unitários + 151 de integração com PostgreSQL real), 0 falhas (2026-10-28, Spring Boot 4.1.2) |
| Agente `mvn test` | 36 testes, 0 falhas (2026-10-27) |
| Web `npm test` + build | 95 testes (59 painel + 36 app do morador), builds OK (código de saída conferido) |
| `scripts/e2e-contingency.sh` · `e2e-agent-update.sh` · `test-agent-launcher.sh` · `verify_keycloak.py` | OK (reexecutados em 2026-10-27; o `e2e-contingency.sh` apaga o banco de desenvolvimento, e o `e2e-agent-update.sh` e o `e2e-browser.py` tiveram defeitos de teste corrigidos) |
| `scripts/e2e-oidc-browser.py` (Chromium real + Keycloak) | **novo, OK, 15 verificações** (login do morador, renovação do token, TOTP do operador, sair encerra a sessão, OTP errado recusado) |
| `scripts/e2e-browser.py` (com `E2E_LIVE_VIDEO=1`) | OK, 54 verificações, **sem tolerar nenhum 403** (inclui o painel como síndico e como administrador da empresa no navegador, "Meus acessos", foto sintética com revogação, câmeras ao lado da mesa, o morador vendo ao vivo a câmera liberada) |
| `scripts/restore-drill.sh` | PASS (21 migrações, V1–V21; 2026-10-27) |
| `scripts/api-smoke.py` | OK (2026-10-27): 55 GETs de condomínio × 4 papéis; só 3 endpoints sem tela (agente e contador de não lidas); **achou um vazamento real** em `me/accesses/policy`, já corrigido |
| `scripts/load-test.py` | reexecutado em 2026-10-27 depois da trava de reservas: 0 erros nos 6 cenários (leitura 1.200–2.500 req/s, validar credencial ~1.200 req/s, leitor com identidade ~300 req/s). **"Meus acessos" auditado deu 400–650 req/s contra 1.550 em 26/10:** a máquina estava carregada (load ~15 com navegador) e o banco de desenvolvimento acumulou ~15 mil eventos de leitor das execuções repetidas; **não comparei com o jar anterior**, então não afirmo que não houve regressão. Reavaliar em banco limpo. O script agora reconecta a conexão de preparação ociosa |
| `scripts/e2e-two-instances.py` | **novo, OK (2 execuções, 2026-10-27):** duas JVMs reais no mesmo banco, equipamento Dahua falso; nunca mais de 1 conexão de eventos; SIGKILL; instância nova assume |
| CI no GitHub | **verde em `9183976` (7 jobs), a `main` está enviada (2026-10-28).** Histórico: `66f4fca` falhou só no gitleaks (senha de teste fixa no `e2e-oidc-browser.py`, trocada por senha aleatória; a antiga continua no histórico: sintética, usuário descartável do Keycloak de desenvolvimento). O CI **não sobe** Keycloak, Grafana nem Prometheus: essas partes só foram provadas na minha máquina |
| Câmera real, controladora real, celular real, Safari/iPhone | **nunca testados** (o Keycloak em Chromium real foi provado em 2026-10-27) |

## 1.1 Plano de ação (ordem recomendada)

Legenda: **[eu]** posso fazer agora, sem decisão sua; **[você]** depende de decisão, contrato, equipamento ou jurídico.

**Dependências (2026-10-27):** `npm audit` 0 vulnerabilidades; aplicadas só as atualizações menores seguras (`hls.js` 1.7.3, `@testing-library/*`, `@types/qrcode`, springdoc 3.2.0); `.github/dependabot.yml` criado (semanal, versões maiores ignoradas). **Adiadas de propósito:** React 19, `@vitejs/plugin-react` 6, jsdom 30, Flyway 13 (gerenciado pelo Spring Boot), Spring Boot 4.2 (só há RC). **Falta:** varrer as imagens de contêiner (trivy/grype) e fixar digests (`postgres:17`, `eclipse-temurin:26-jre` são tags móveis), regerar `docs/inventory/`.

**Rodada 2026-10-27 (sem dependências externas):**
- **Revisão de código do backend (checklist `java-code-review`):** busca por exceção engolida, `Optional.get`, `printStackTrace`, `EAGER`, `findAll`, `synchronized`/`sleep` e `@Transactional` em controller. **Achado real:** `AgentController` e `AgentAdminController` tinham regra de negócio e transação no controller (entrega de comandos ao agente, resolução de conflito com auditoria), contra o ADR 0007. **Corrigido:** novos `AgentCommandService` e `SyncConflictService`; os controllers só traduzem HTTP. `mvn verify`: 31 + 151, 0 falhas. Os `catch` genéricos restantes (9) foram lidos: todos são fronteiras de resiliência com log ou resposta segura; o do `SimulatorSnapshotSource` captura `Error` de propósito (fonte de imagem de teste) e fica como nota.
- **Javadoc:** 675 métodos/construtores públicos documentados (em português, como o restante do código; só comentários, nenhum corpo alterado). Os de controller descrevem a rota (`VERBO caminho`), os de serviço e entidade descrevem o comportamento. **Limite:** várias descrições de `@param` são o nome do campo em palavras; vale revisar à mão os serviços centrais. Restam 15 membros públicos sem Javadoc (tipos aninhados e utilitários).
- **SOLID/DIP (`java-solid-dip`):** não há `new` de serviço/repositório no backend (injeção pelo construtor do Spring); as duas ocorrências do agente estão na raiz de composição (`Main`), que é o lugar certo.
- **Telas mais fluidas (`web/shared/src/theme.css`, `api.ts`):** transições curtas em botões/cartões/campos, feedback de toque (`:active`), entrada suave das seções e listas, barra fina no topo enquanto uma gravação está em andamento, esqueletos de carregamento no lugar de "Carregando…" (login, "Meus acessos", último acesso), alvos de toque de 44 px em celular/tablet, foco visível em links e cartões, `touch-action: manipulation` (tira o atraso do toque) e respeito a "reduzir movimento". **Gravações idênticas em andamento são reaproveitadas** (duplo clique em "Registrar" não cria dois registros). 4 testes novos; `npm test` 63 + 36 e build com código de saída 0; `e2e-browser.py` com vídeo: 54 OK. **Não há skill de UX/UI instalada neste ambiente:** as escolhas seguem o que o código mostrava (zero transições, zero tratamento de movimento reduzido) e **não foram avaliadas em celular real nem por usuários**.
- **Imagens de contêiner:** varridas (trivy) e digests fixados nas tags móveis; resultado em `docs/security.md`. O Dependabot abriu 11 pedidos; em 2026-10-28 foram **aplicados** Spring Boot 4.1.2, Keycloak 26.8.1, as atualizações menores do agente e do web e as 4 ações do GitHub (ver `docs/security.md`). **Ficam de fora:** Grafana 13 (maior, AGPL), Prometheus 3.15 (sem teste) e Postgres 18 (maior, exige migração do diretório de dados).

**Revisão de concorrência (2026-10-28, checklist `concurrency-review`):** sem `@Async`, `ThreadLocal`, `synchronized` no backend (só no `LocalStore` do agente, de propósito: um arquivo, acesso serializado) e os 5 `volatile` têm justificativa (parada de laço). Achados e correções:
- **Limitador de taxa (`RateLimitFilter`):** `failures` e `blockedUntil` só encolhiam quando a mesma origem voltava; uma varredura de origens diferentes fazia a memória crescer sem limite. Agora as três tabelas são varridas, no máximo uma vez por janela. Teste novo (2) que **falha sem a correção**.
- **Agendamentos numa única thread:** o pool padrão do Spring tem 1 thread, então uma reconciliação lenta da assinatura Dahua atrasaria a expiração de comandos físicos. `spring.task.scheduling.pool.size: 4`.
- **`MediaSnapshotSource`:** a leitura bloqueante do ffmpeg usava o pool comum do ForkJoin; passou a uma thread virtual.
- **Defeito meu da rodada anterior:** o Javadoc que gerei ficou **depois** das anotações (`@PostMapping`…), o que o `javadoc` não associa ao método. 262 blocos movidos para antes das anotações; nenhum restante.
- `mvn verify`: 33 + 151, 0 falhas; `load-test`: 0 erros nos 6 cenários. Os limites de um limitador em memória por instância continuam na Fase 5 (Redis).

**Adaptador de notificações externas (D5):** não iniciado de propósito. Sem o provedor escolhido e sem os textos jurídicos (dado pessoal sairia da plataforma), uma interface sem implementação real seria especulativa. Quando houver provedor: adaptador próprio, desligado por padrão, sem copiar o texto da mensagem para o log.

**Fase 1 — Endurecer o que já existe (pequeno, sem dependência) [eu] — CONCLUÍDA em 2026-10-26** (evidência na tabela; B12 reduzido a 3 min com renovação do player, a URL repassada ainda funciona até vencer)
1. **CR1:** travar o teto de 4 câmeras liberadas (trava por condomínio no serviço ou restrição no banco) e teste de concorrência.
2. **B12:** token do vídeo preso só ao caminho, sem ligação com a pessoa: quem receber a URL assiste até o token vencer (10 min). Reduzir o prazo para o morador (2–3 min, com renovação) e registrar a renovação.
3. **B14:** versionar a configuração local do servidor de mídia e um `scripts/dev-media.sh` que sobe as fontes de teste (hoje vive em `/tmp`, fora do Git, e quebra sem aviso para qualquer outra máquina).
4. **B18:** E2E com o papel de síndico no navegador (cadastro de câmera, "Liberar ao morador", "Meios de acesso"); hoje só o vitest cobre essas telas.
5. **B15/B16:** esqueleto do painel enquanto as permissões carregam (hoje os botões aparecem depois de um instante) e grade 2×2 do vídeo no celular.
6. **B13:** `api-smoke` e `load-test` esperam o limitador de taxa esvaziar (hoje dão 429 falso logo depois de outra prova).
7. **Processo:** o `npm run build` do painel falhou por erro de tipo num teste e só apareceu porque conferi a saída; passar a rodar `npm test && npm run build` com código de saída verificado antes de cada commit.

**Fase 2 — Telas de administração da plataforma e da empresa — IMPLEMENTADA em 2026-10-26, com as regras de papel que o servidor já aplicava (D8 não foi respondido: usei o que a API já impõe) e SEM planos, cobrança ou limites por empresa (D7 continua aberto)**
Empresas operadoras, administradores, condomínios, agentes locais, rotação de chave de dispositivo e conflitos de sincronização. Hoje só existem na API (11 endpoints sem tela). **Primeiro:** você responde D8 (o que cada papel vê e faz) e D7 (planos e limites).

**Fase 3 — Equipamento real [você fornece, eu ajusto]**
1. Uma câmera RTSP real: testar codec (H.264), caminho RTSP, captura de quadro e a ciência de que o HLS exige H.264.
2. Uma controladora Dahua/Intelbras: confirmar `CardNo`/`UserID`/`Status` (B6/MA1), `openDoor` e o sentido por leitor (D20). Roteiro: `docs/reference-analysis/DAHUA_SOURCES.md`.
3. Depois disso: agente local publicar o vídeo de câmeras atrás de NAT, ONVIF `GetStreamUri`, passagens do morador durante a queda da central (MA3), cadastro guiado de câmera (CR5, decisão D4).

**Fase 4 — Bloqueio jurídico (em paralelo, é você com o jurídico)**
**Atualização 2026-10-26 (informada pelo responsável, não verificável por mim):** o jurídico respondeu; o contrato e a conformidade com a LGPD foram aprovados e assinados e serão implementados no projeto. **Os documentos não estão no repositório**; sem o texto (bases legais, prazos de retenção, texto de consentimento, termos de uso, decisão sobre foto e vídeo do morador) não implemento nem declaro conformidade. Ao receber, registrar em `docs/juridico/` (sem dados pessoais) e transformar em: textos e versões de consentimento, prazos de retenção por dado, aceite de termos no app, e a decisão de ligar ou não foto de cadastro e vídeo do morador.
D1, D16, D17, D19, D20, D21 e as 9 perguntas da seção 6. **Enquanto não responderem:** foto de cadastro e vídeo do morador ficam desligados em produção e não há identificação facial.

**Fase 5 — Produção e escala [você + eu]**
D3 (cofre de chaves/KMS), D4 (chave de lançamento do agente), D5 (e-mail/push/WhatsApp), D6 (infraestrutura). Em seguida: limitador de taxa em Redis, lock distribuído nos agendamentos (a eleição de líder da conexão Dahua, B7, já foi feita), mTLS por agente/dispositivo, réplica e PITR do banco, pentest independente, OIDC em navegador real, PWA em celular real.

**Fase 6 — Lançamento comercial [você]**
Busca de anterioridade da marca (L1), varredura de proveniência com ferramenta comercial (L3), parecer sobre o Grafana/AGPL (L5), RIPD e DPO.

## 1.2 Bugs e riscos encontrados nesta rodada (2026-10-26)
| # | Achado | Gravidade | Estado |
|---|---|---|---|
| B11 | Teste do painel com erro de tipo quebrava o `npm run build` e passou despercebido por eu ler só o fim da saída | média (o CI teria falhado) | **corrigido**; processo na Fase 1, item 7 |
| B12 | **Token do vídeo preso só ao caminho da câmera:** a URL HLS pode ser repassada e funciona até vencer (10 min) | média | parcial: 3 min (era 10); o token continua sem ligação com a pessoa |
| B13 | Limitador de taxa devolve 429 ao `api-smoke` logo depois do e2e/carga (falso negativo da prova) | baixa | **corrigido** (smoke repete em 429; load-test avisa e aborta) |
| B14 | A configuração do servidor de mídia usada nos testes (`/tmp/domus-run/mtx-test.yml`) e as fontes de vídeo não estão no repositório; outra máquina não reproduz o e2e de vídeo | média (reprodutibilidade) | **corrigido** (`scripts/dev-media.sh up|down|status`, config gerada de `deploy/media/mediamtx.yml`) |
| B15 | Painel: os botões de administração aparecem só depois da consulta de permissões (piscada) | baixa | **corrigido** ("Carregando permissões…") |
| B16 | App do morador: 4 vídeos empilhados formam uma página muito longa no celular | baixa (estética) | **corrigido** (grade 2×2 no celular, não testado em aparelho real) |
| B17 | HLS no iPhone/Safari (reprodução nativa) e reprodução automática **não foram testados**; só Chromium | média | aberto |
| B18 | E2E de navegador roda como operador: telas do síndico só têm teste de componente | média | **corrigido** (bloco "painel como síndico" no e2e) |
| B19 | Liberar câmera notifica todos os moradores, inclusive dependentes (CR2) | baixa | depende de D17/D21 |
| CR1 | Teto de 4 câmeras sem trava de concorrência | baixa | **corrigido** (`SELECT … FOR UPDATE` no condomínio; teste com 8 liberações simultâneas = exatamente 4) |
| B21 | `BootstrapAdmin` criava o administrador inicial sem `email_hash` | baixa | **corrigido (2026-10-27)** (recebe o `FieldCipher`; teste unitário confere o hash) |
| B22 | `scripts/dev-media.sh` não reiniciava uma fonte de teste que morreu (foi a causa do "Sem imagem" do Hall) | baixa (ambiente de teste) | **corrigido (2026-10-27)**: cada fonte reabre o ffmpeg sozinha (testado matando o processo) e há `dev-media.sh repair` |
| B23 | O player mostrava a mesma mensagem para servidor de mídia fora do ar e para câmera sem fonte | baixa (UX) | **corrigido (2026-10-27)**: a mensagem vem do código HTTP do hls.js (sem resposta = servidor de mídia; 404 = câmera sem vídeo; 401/403 = recusado). Não validado com câmera real |
| B24 | **"Sair" não encerrava a sessão no Keycloak:** sem `id_token_hint` ele só mostrava uma tela de confirmação e a pessoa continuava logada. Achado pelo teste OIDC em navegador real | média (segurança em computador compartilhado, como a portaria) | **corrigido (2026-10-27)**: o `id_token` fica só em memória e vai como dica no logout; teste de unidade e e2e |
| B26 | **Duas reservas concorrentes do mesmo espaço podiam entrar em deadlock** no Postgres (verificação da exclusion constraint), e a API devolvia 500 em vez de 409. Achado pela intermitência do teste de concorrência (1 em ~6 execuções) | média | **corrigido (2026-10-27)**: a área comum é travada (`PESSIMISTIC_WRITE`) durante a reserva; teste novo com 25 rodadas concorrentes que falhava antes da correção |
| B27 | **O fluxo de eventos Dahua reconectava a cada ~6 s:** o timeout de 5 s do pedido HTTP valia também para a leitura do fluxo, que nunca termina. Com equipamento real isso abriria uma janela de eventos perdidos a cada reconexão. Achado pelo teste das duas instâncias | alta (confirmação da porta) | **corrigido (2026-10-27)**: o prazo passou a valer só para os cabeçalhos; o socket é fechado quando a assinatura é interrompida; teste que falha sem a correção. **Não validado com equipamento real** |
| B25 | O `me/accesses/policy` respondia 200 a usuário de outro condomínio. Achado pelo `api-smoke` | baixa (o prazo não é dado pessoal) | **corrigido (2026-10-27)**: só morador do condomínio; os demais recebem 404; teste |
| B20 | Síndico/administração no painel: a consulta de atendimentos e turno (só de operador) devolvia 403 uma vez por login. Achado pelo e2e como síndico | baixa | **corrigido (2026-10-27):** `GET /me/admin-scope` passou a informar `operator`; o painel só consulta atendimentos e turno de quem é operador. A tolerância ao 403 ficou só como reserva se o escopo falhar; o e2e agora **não aceita nenhum 403** de síndico/administrador |

## 1.3 Resumo do que está pendente (avaliação de 2026-10-28)

**Bugs abertos (nenhum bloqueia o que já foi provado em simulação):**
| # | Situação |
|---|---|
| B6 | Campos do evento Dahua/Intelbras não confirmados em equipamento real — **depende de controladora** |
| B8, B9 | Riscos aceitos (revogação offline até 6 h; cota offline por agente) |
| B10 | Passo documentado para Keycloak já existente |
| B12 | Token de vídeo sem ligação com a pessoa (3 min) — **parcial, risco aceito** até haver sessão por pessoa |
| B17 | HLS em iPhone/Safari **nunca testado** — depende de aparelho |
| B19 | Notificação de câmera liberada chega a dependentes — depende de D17/D21 |

Todos os demais (B1–B5, B7, B11, B13–B16, B18, B20–B27, CR1) estão **corrigidos** com teste.

**Pendências que ainda posso fazer sem terceiros (pequenas):**
1. Aplicar o `actions/checkout` 7 (PR #12; CI do PR verde, 7 jobs): atualização de ação do GitHub, baixo risco. **Aguardo seu aval**, pois mexe em `.github/workflows/ci.yml` e exige novo push.
2. Rodar `e2e-browser.py` de novo (não foi reexecutado depois do último commit de concorrência).
3. Reavaliar "meus acessos" no teste de carga em banco limpo, comparando com o jar anterior (hoje não afirmo ausência de regressão).
4. Revisar à mão os `@param` do Javadoc dos serviços centrais e documentar os 15 membros restantes.
5. Regerar `docs/inventory/` (dependências e licenças) após as atualizações de 2026-10-28.
6. Testes de integração por domínio (item 12 da seção 4) e relatório por unidade.

**Estado dos pedidos do Dependabot (2026-10-28):** aplicados no código e enviados: Spring Boot 4.1.2 (#10), Keycloak 26.8 (#3, mesclado), agente (#6), web (#11, fechado), `gitleaks-action` 3, `setup-java` 6, `setup-node` 7, `upload-artifact` 7. #10 e #6 seguem abertos no GitHub mas o conteúdo já está na `main`; fechar à mão se não fecharem sozinhos. **Adiados:** #1 Grafana 13 (maior, AGPL), #7 Prometheus 3.15 (sem teste), #2 Postgres 18 (fechado; maior, exige migração do diretório de dados). **Em avaliação:** #12 `actions/checkout` 7.

**Depende de você, equipamento ou terceiros (sem mudança):** documentos jurídicos em `docs/juridico/` (D1, D16–D21), D3–D8, provedor de notificação (D5), infraestrutura (D6), câmera/controladora/celular/Safari reais (B6, B17, MA1, MA3, Fase 3), busca de marca (L1), varredura de proveniência (L3), parecer Grafana/AGPL (L5), RIPD/DPO, saída automática de visitante esquecido (decisão de produto), Redis para limitador, locks distribuídos, mTLS, réplica/PITR e pentest.

## 2. Melhorias já aplicadas (resumo)
- Plataforma multi-tenant com auditoria imutável, central de operações, app do morador, painel, agente de contingência offline com sincronização idempotente.
- Dispositivos: contrato de adaptadores, simulador, HTTP genérico, ONVIF, relé do agente e **Dahua/Intelbras** (comando + assinatura de eventos), com a regra "aceito ≠ aberto".
- Segurança: Keycloak com MFA por papel, PKCE, **audiência do token**, **renovação silenciosa do token**, limitação de taxa, anti-SSRF/XXE, **criptografia de dados pessoais em nível de campo** (AES-256-GCM, índice cego para placa, rotação e migração), **armazenamento do agente cifrado em repouso**, **atualização segura do agente** (Ed25519, anti-retrocesso, rollback).
- Operação: métricas, logs JSON, tracing opcional, backup com drill, stack de produção, **tema Domus** nos apps e no login do Keycloak.
- Código: pacote raiz `br.com.domus.platform`; domínios divididos em `controller`/`service`/`repository`/`entity` ([ADR 0007](docs/adr/0007-organizacao-de-pacotes.md)).

## 2.1 Fluxos de portaria (feedback do teste local, 2026-10-23)
| Etapa | Situação |
|---|---|
| 1. Retorno da chamada ao morador | **Feito.** Notificação ao ser atendida e ao ser encerrada (com o desfecho, sem a justificativa interna), `GET me/calls`, status "aguardando / em atendimento / encerrada" na tela, atualização automática a cada 5 s, uma chamada ativa por vez |
| 2. App do morador: início, visitas, encomendas | **Feito.** Cartões de encomendas a retirar e visitas esperadas, aba Visitas (esperadas, chegaram, histórico de entradas com o nome do convite), encomendas pendentes em destaque |
| 3. Painel: encomendas e visitantes | **Feito.** "Mesa do condomínio": registrar chegada, entregar com nome de quem retirou, entradas recentes e visitantes |
| 4. Painel: portas e dispositivos | **Parcial.** Estado das portas, atualização de estado e histórico de comandos (com "aceito ≠ aberto"). A abertura pela portaria só existe dentro de um atendimento (decisão: é lá que justificativa e base de autorização ficam registradas); a API aceita também a base "credencial validada há menos de 5 min", sem tela |
| 5. Câmeras | **Parcial.** Cadastro, estado e **vídeo ao vivo (RTSP → HLS pelo MediaMTX, com token curto e auditoria)**, provado com uma fonte RTSP de teste (`docs/cameras.md`). **Falta**: teste com câmera real, câmera atrás de NAT (o agente local ainda não publica o fluxo), ONVIF `GetStreamUri`, gravação (decisão LGPD) |
| Lote A (escopo de produto) | **Feito:** autorização de visitante pelo celular e alerta de pânico. Ver `docs/product-scope.md` para os lotes B e C |
| Lote B (escopo de produto) | **Feito (2026-10-24):** saída de visitantes, relatório de acessos com CSV, comunicados, livro de turno, comprovante imprimível, abertura remota pelo morador (opcional por condomínio; desligada por padrão) e mensagens individuais morador ↔ portaria (2026-10-25). Evidência: 134 testes de backend, 52 de tela, E2E em navegador (31 verificações, ainda sem o passo de mensagens), `restore-drill` PASS (12 migrações), teste de carga sem erros |
| Rodada 2026-10-25/26 | **Feito:** cadastros (moradores, cônjuge, dependentes, animais, equipe), vídeo ao vivo, alerta de pânico em tela cheia com sirene e unidade de origem, notificações do morador por assunto, encomendas a retirar (contagem, lembrete), aviso de visita/prestador, entrada e saída de visitante autorizado na portaria, "voltar" em todas as telas |
| Fora do plano | Notificação push/e-mail/WhatsApp ao morador (D5); aderência a outros produtos de portaria depende de você descrever os fluxos desejados |

## 3. Bugs

### Corrigidos (por teste ou carga, não por suposição)
- Deadlock do pool de conexões na auditoria sob carga (`REQUIRES_NEW`): `GET moradores` caía de 3.000 req/s para 1 req/s; teste de regressão `PoolStarvationIT`.
- Métricas de negócio que nunca atualizavam (`ResultSet` sem `next()`).
- Testes de integração que "passavam" sem rodar (failsafe não ligado).
- Formulário de perfil sobrescrevia o que a pessoa digitava quando a carga chegava depois (e pioraria com a renovação do token); teste que falha sem a correção.
- CI: teste de bootstrap dependente da ordem de execução e referência inexistente do `osv-scanner`.
- Healthcheck do contêiner de produção, caminhos do Caddy, scripts com caminho contendo espaços.

### Abertos / riscos conhecidos
| # | Item | Impacto | Como tratar |
|---|---|---|---|
| ~~B1~~ | CI | **RESOLVIDO (2026-10-22):** 7 jobs verdes (backend, agente, web, compose, CodeQL Java e JS/TS, OSV+gitleaks). Corrigido: CodeQL por linguagem, vulnerabilidades reais (Boot 4.0.9, Jackson, OpenTelemetry 1.62, assertj) e o OSV, que não resolvia o POM do backend e agora usa SBOM CycloneDX |
| ~~B2~~ | Desempenho após a criptografia | **RESOLVIDO (2026-10-22):** A/B/A/B/A/B na mesma máquina (jar anterior à cifra × atual): sem diferença além do ruído (±40% entre rodadas, mesmo com o mesmo jar); a cifra custa ~2 µs/op (≈0,1 ms por página de 20 moradores, ~1% da requisição). A queda de 3.000 req/s vinha do ambiente (k3s, navegador). Medição em hardware dedicado continua desejável para dimensionar produção |
| ~~B3~~ | Placas eram listadas por data de cadastro (a ordenação pela placa cifrada seria aleatória) | — | **RESOLVIDO (2026-10-27):** `?sort=plate,asc` ordena em memória sobre o texto decifrado, valendo em todas as páginas (teste com páginas de 2); recusa acima de 5.000 veículos por condomínio; `?plate=` busca por igualdade pelo índice cego |
| ~~B4~~ | E-mail do usuário deixou de ser único e não havia busca | — | **RESOLVIDO (2026-10-27) para o e-mail:** migração V20 (`app_user.email_hash`, único); mesmo e-mail em outra conta = 409 (caixa e espaços ignorados); `GET /condominiums/{id}/members?email=` busca por igualdade. **Continua sem busca parcial nem por nome** (dado cifrado): se a portaria precisar, decidir entre índice de prefixos/tokens cegos (vaza padrão de frequência) ou filtro no cliente sobre a página |
| ~~B5~~ | Trocar `BLIND_INDEX_KEY` quebrava a unicidade das placas | — | **RESOLVIDO (2026-10-27):** `BlindIndexReindexer` confere uma amostra na partida e recalcula placa e e-mail se a chave mudou (nada se perde: o hash vem do texto decifrado); duplicados legados ficam sem índice com aviso. Procedimento em `docs/operations.md` (parar todas as instâncias antes de trocar). Não testado em banco de produção real |
| B6 | Suposições **não confirmadas** do Dahua/Intelbras: campos `Status`/`UserID` do evento, parâmetro `heartbeat`, `openDoor` em controladoras (só visto em VTO) | a confirmação de abertura pode nunca chegar (o comando termina `UNKNOWN`, falha segura) | roteiro de campo em `docs/reference-analysis/DAHUA_SOURCES.md` |
| ~~B7~~ | Cada instância da API abria a própria conexão de eventos Dahua | — | **RESOLVIDO (2026-10-27) para a conexão de eventos:** posse por dispositivo (V21 `device_event_lease`, 90 s, relógio do banco, renovada a cada 30 s; testes de disputa com 12 instâncias e de takeover; dono cai = outra assume em até ~2 min). **Provado com duas JVMs reais** (`scripts/e2e-two-instances.py`: nunca mais de 1 conexão aberta, SIGKILL da dona, instância nova assume), contra um equipamento falso. Os agendamentos (expiração de comandos, retenção, métricas) e o limitador de taxa ainda rodam em cada instância: ver Fase 5 |
| B8 | Revogação de credencial durante a queda só chega ao agente quando a política renova (TTL 6 h) | janela limitada e auditável | risco aceito |
| B9 | Quota offline é por agente: dois agentes offline podem estourar uma credencial multiuso | vira `QUOTA_EXCEEDED` na volta | risco aceito |
| B10 | Em Keycloak já existente, o mapper de audiência precisa ser adicionado, senão os logins recebem 401 | falha no upgrade | passo documentado em `docs/operations.md` |

## 4. Próximas ações (ordem sugerida)
**A. "Meus acessos" do morador, com a foto do cadastro, preparando a identificação facial — FEITO com leitores simulados e foto desligada por padrão (2026-10-26); a identificação facial NÃO foi feita.**
**Plano detalhado: [`docs/plano-meus-acessos.md`](docs/plano-meus-acessos.md)** (fases, modelo de dados, decisões novas D19 prazo de retenção e D20 sentido de entrada/saída).
O morador vê os **horários de entrada e saída** dele no condomínio e a **foto do cadastro**. A foto existe porque, no futuro, a entrada será por
**identificação facial**. **Entregue:** migrações V17/V18; identidade de acesso do morador; registro de passagens só de cadastrados; sentido por ponto de acesso; API e tela "Meus acessos" + "Último acesso"; auditoria; retenção; exportação e anonimização; foto com consentimento revogável (desligada); tela da portaria para cadastrar o meio de acesso (síndico); testes `ResidentAccessIT` (8), `ResidentPhotoIT` (5), testes de tela e E2E em navegador. **Limites e pendências que ficam (tratar depois): lista "Limites do Meus acessos" abaixo.** Escopo original, em etapas (cada uma com testes, E2E e documentação; recurso novo vem **desligado**):
1. **Lacuna de hoje:** só existe histórico de acesso de **visitantes** (convite validado, visitante autorizado). **Não há registro de entrada e
   saída do próprio morador**. Primeiro é preciso um registro de acessos do morador: identidade de acesso do morador (cartão, QR do morador ou
   a identificação do equipamento, ex.: o `UserID` do evento Dahua/Intelbras, que hoje é uma suposição não confirmada em campo, B6), eventos de porta
   ligados ao morador e **sentido** (entrada ou saída: exige leitor nos dois sentidos ou pareamento; sem isso só se sabe "passou").
2. **Tela "Meus acessos"** no app: lista com data, hora, entrada ou saída e o ponto de acesso, filtro por período; só **os do próprio morador**
   (a pessoa não vê os de outro morador da unidade, a menos que o titular decida o contrário: decisão D17); o síndico/portaria não vê o histórico
   pessoal sem motivo registrado. Cada consulta fica na auditoria. Retenção própria.
3. **Foto do cadastro:** o próprio morador envia (ou a administração, com registro); validação de qualidade (rosto único, tamanho, formato); armazenamento
   **cifrado**, sem URL pública, baixada com o token (mesmo padrão da imagem da entrada); consentimento **específico e registrado** (quando, versão do
   texto, quem consentiu); exclusão e troca pelo próprio titular; menores e dependentes só com consentimento do responsável (D17). A foto aparece em "Meus
   acessos" e na ficha do morador para a portaria.
4. **Separar foto de biometria:** a foto de cadastro **não** vira modelo facial (gabarito biométrico) nesta etapa. O gabarito é dado **sensível**
   (LGPD, art. 5º, II, e art. 11) e só se cria depois das decisões D16 a D18. O desenho já deve prever que o gabarito fique **no equipamento** (terminal
   facial), e não na central, e que exista **alternativa sem biometria** (cartão, QR) para quem não consentir.
5. **Identificação facial (futuro):** depende de equipamento, de D16 a D18 e do relatório de impacto (RIPD). Não será implementada antes disso.

*Limites que precisam ficar travados em código, não só em texto* (a preocupação é que aplicativos extrapolam o que informaram ao usuário): finalidade
única e declarada na tela (controle de acesso); nenhum uso secundário da foto (marketing, análise de comportamento, compartilhamento) e nenhuma
exportação em massa; cada visualização da foto e do histórico na auditoria; prazo de retenção e exclusão efetiva, inclusive ao sair do condomínio;
tela de transparência explicando o que é coletado, por quê, por quanto tempo e como revogar; revogar o consentimento desliga o uso sem bloquear
a entrada da pessoa. **Observação honesta:** não verifiquei as decisões judiciais ou administrativas que você citou sobre aplicativos que superaram os
limites informados, e este projeto não declara conformidade com a LGPD. Antes de coletar a foto ou usar reconhecimento facial é preciso **parecer jurídico**
(decisões abaixo).

**Limites do "Meus acessos" (declarados no plano; tratar depois):**
| # | Limite | O que fazer |
|---|---|---|
| MA1 | **Nada validado com equipamento real:** os campos `CardNo`/`UserID` do evento Dahua/Intelbras são suposição (B6); a identificação da pessoa só foi provada com leitores simulados | roteiro de campo em `docs/reference-analysis/DAHUA_SOURCES.md`; ajustar o mapeador depois |
| MA2 | **Sentido:** com um leitor só (sem `direction` IN/OUT no dispositivo) o registro é "passagem"; não existe "quem está dentro agora" (exigiria antipassback). Decisão **D20** | decidir como instalar os leitores; a tela de cadastro de controladoras ainda não existe (o `direction` hoje é configurado só pela API) |
| MA3 | **Contingência:** o agente local não lê o histórico da controladora, então as passagens de moradores durante uma queda da central **não** chegam ao "Meus acessos" | agente passar a enviar passagens (idempotente) |
| MA4 | **Retenção de 90 dias é recomendação**, não decisão. Decisão **D19** | confirmar com o jurídico; configurável (`RESIDENT_ACCESS_RETENTION_DAYS`) |
| MA5 | **Só o titular vê o histórico:** moradores da mesma unidade não veem os dos outros, nem síndico, nem portaria; sem fluxo de acesso excepcional (ordem judicial, apuração de incidente) | decidir o fluxo e o registro dele (D17) |
| MA6 | **Dependentes e menores** ficam fora da foto e do histórico (a foto de dependente é recusada; dependente sem conta de app não vê nada) | D17 |
| MA7 | **Foto sem detecção de rosto:** só valida formato, tamanho (≤ 2 MB), dimensão mínima (160 px) e proporção (≤ 3:1); a qualidade depende de quem envia; a tela diz isso | decidir e licenciar uma biblioteca de visão computacional, se for necessário (a fase de reconhecimento facial vai exigir) |
| MA8 | **Foto desligada por padrão** (`RESIDENT_PHOTO_ENABLED=false`); ligar em produção depende de **D16** (base legal e consentimento) e do parecer jurídico | parecer jurídico; revisar o texto do consentimento (versão `2026-10-v1`) |
| MA9 | **Cadastro do meio de acesso só pelo síndico/administração:** o morador não cadastra o próprio QR/app; a portaria (operador) não cadastra | decidir se o morador pode cadastrar QR/app |
| MA10 | **Captura de tela** da foto ou do histórico pelo morador não tem como ser impedida; **dispositivo comprometido** pode forjar passagens do próprio ponto de acesso | risco aceito; mTLS por dispositivo (B-seg) |
| MA11 | **Desempenho:** evento de leitor com identidade = ~280 req/s por dispositivo no teste (contenção na linha do dispositivo; leitores distintos não disputam) | reavaliar com leitores reais e, se preciso, não atualizar o `status` a cada evento |
| MA12 | **Identificação facial:** só reservada (`method=FACE`, `kind=FACE_REF` no banco); **não existe** `ENROLL_FACE` no contrato de adaptadores, gabarito, nem alternativa de cadastro no terminal | depende de D16 a D18, RIPD e terminal facial |
| ~~MA13~~ | Texto "apagados em 90 dias" era fixo | **corrigido (2026-10-27)**: `GET me/accesses/policy` devolve o prazo e a tela o mostra |

**Limites do vídeo para o morador (2026-10-26):**

| # | Limite | O que falta |
|---|---|---|
| CR1 | ~~O teto de 4 câmeras liberadas era verificado sem trava~~ — resolvido em 2026-10-26 (trava por condomínio e teste de concorrência) | — |
| CR2 | Qualquer morador vinculado ao condomínio (inclusive dependente) vê as câmeras liberadas; não há horário nem restrição por unidade | decisão D21 |
| CR3 | Mesmos limites do vídeo da portaria: nenhuma câmera real testada, H.264 apenas, câmera atrás de NAT depende do agente publicar o fluxo | `docs/cameras.md` |
| CR4 | O app não impede captura de tela nem gravação do celular | só aviso na tela |
| CR5 | **Cadastro guiado de câmeras (usuário e senha cifrados no banco, hoje só o nome de uma variável de ambiente) e leitura do endereço pelo ONVIF `GetStreamUri`:** não feitos; mudam o modelo de segredos (decisão D4) e dependem de câmera real | decidir D4; testar com câmera real |
| CR6 | A "ciência" do morador vale só enquanto a tela está aberta; não é registrada nem é consentimento jurídico | D21 |

**B. Demais pendências técnicas que podem ser feitas sem definição externa (em ordem sugerida)**
1. ~~Validar o CI (B1)~~ e ~~medir o desempenho (B2)~~: feitos em 2026-10-22.
2. **Telas de câmeras e imagem da entrada junto do pedido de visita — FEITO com câmera simulada (2026-10-26):** grade, tela cheia, editar/desativar, câmera de entrada, captura com prévia, imagem cifrada, retenção e visão no app. Ver [`docs/plano-cameras-visitas.md`](docs/plano-cameras-visitas.md). **Falta:** câmera real (captura HTTP/ONVIF e compatibilidade de codec), D12–D15 formalizadas (o recurso vem **desligado** por padrão) e o parecer jurídico D13.
3. **Agente local publicar o vídeo das câmeras** no MediaMTX (câmera atrás de NAT) e ONVIF `GetStreamUri`; hoje o vídeo só funciona com RTSP alcançável pela central (`docs/cameras.md`).
4. ~~Login OIDC e renovação do token em navegador contra o Keycloak real~~ — **FEITO (2026-10-27)** em Chromium (`scripts/e2e-oidc-browser.py`). Falta o mesmo em celular real e Safari (B17).
5. **Telas de administração da plataforma e da empresa operadora — FEITO (2026-10-26), faltam D7 (planos/limites/cobrança) e a confirmação de D8.** Botão "Administração" no painel (aparece só para quem administra algo, via `GET /me/admin-scope`): Empresas e administradores (plataforma), Condomínios (criar/listar), Agentes locais (cadastro com credenciais mostradas uma só vez, revogação, estado online), conflitos de sincronização (resolver) e "Girar chave" de equipamento (com confirmação, na aba Portas, só síndico/administração). Não há ainda: listar/revogar administradores de empresa, editar/suspender empresa ou condomínio. Escopo original:
   - **Administrador da plataforma:** cadastrar e listar empresas operadoras (`/companies`) e os administradores de cada empresa (`/companies/{id}/admins`); criar condomínios; visão de todas as empresas.
   - **Administrador da empresa operadora:** criar e gerenciar os condomínios da empresa, operadores e síndicos (hoje só a equipe de um condomínio, na aba Equipe), agentes locais (`/condominiums/{id}/agents`: cadastro e chave), rotação de chave de dispositivo (`rotate-key`) e conflitos de sincronização (`/sync-conflicts`).
   - **Antes de começar, decidir D8** (o que cada papel pode ver e fazer) e D7 (planos e limites por empresa). As telas do síndico/portaria (cadastros) foram feitas em 2026-10-25.
6. **Escala horizontal:** limitador de taxa compartilhado (Redis) e lock distribuído nos agendamentos (a eleição de líder na assinatura Dahua foi feita em 2026-10-27, B7).
7. **Mais segurança:** mTLS por agente/dispositivo, rotação da chave de lançamento do agente, chaves de criptografia em gerenciador de segredos (KMS) em vez de variável de ambiente.
8. ~~Relatórios por condomínio e exportação~~: feitos — acessos com CSV (Lote B) e, em 2026-10-27, o **resumo da atividade** (encomendas, atendimentos por desfecho e tempos médios, ocorrências e livro de turno; só números, sem dado pessoal; JSON e CSV; aba Relatórios). Não há ainda relatório por unidade nem gráficos.
9. **Qualidade:** teste de carga reexecutado em 2026-10-26 (com "Meus acessos"); PWA do morador sem teste em celular real; sirene do alerta de pânico não foi ouvida em navegador real (só provada por teste que os tons iniciam e param).
10. **Retenção de mensagens** individuais: **expurgo opcional feito (2026-10-27)** (`DIRECT_MESSAGE_RETENTION_DAYS`, padrão 0 = não apaga; o prazo continua sendo decisão sua/jurídica, D1). **Saída automática de visitante esquecido no local** (hoje só aviso após 12 h): depende de decisão de produto, pois muda o registro de presença.
11. **Agente:** pacote `.deb` e suporte além de Linux/systemd.
12. Testes de integração organizados por domínio (hoje ficam todos em `br.com.domus.platform`).

## 5. Pendências que dependem de equipamento, contrato ou terceiros
- **Hardware real:** testar o `DAHUA_CGI` com uma controladora; obter a documentação oficial do modelo exato (NDA/revendedor); teste com perda de conectividade no meio do comando.
- **SIP/telefonia:** exige servidor SIP/mídia. (Vídeo ao vivo das câmeras: feito, ver `docs/cameras.md`.)
- **Notificações push, e-mail e WhatsApp:** exigem provedor e credenciais (WhatsApp, API oficial).
- **Infraestrutura de produção:** réplica e PITR do PostgreSQL, WAF/DDoS, criptografia de disco/banco, varredura de imagens, pentest independente, medição de RTO/RPO.
- **Grafana (AGPL-3.0)** como serviço separado: revisão jurídica se o produto for oferecido a terceiros como serviço.
- **App nativo (React Native):** só se houver necessidade de push nativo, NFC ou lojas.

## 6. Decisões em aberto (do titular do produto)
| # | Decisão | Situação |
|---|---|---|
| D16 | **Base legal e consentimento para a foto de cadastro e para a identificação facial** (dado sensível quando vira gabarito biométrico): texto, versão, registro, revogação, quem é o controlador, RIPD, DPO | em aberto (jurídico); **bloqueia** a coleta em produção e a identificação facial |
| D17 | **Menores e dependentes; quem enxerga o histórico de acessos** (o titular, os moradores da unidade, o síndico, a portaria) e em que condições | em aberto |
| D19 | **Prazo de retenção do histórico de entradas e saídas do morador** (recomendado: 90 dias, configurável) | em aberto; hoje 90 dias por padrão |
| D21 | **Morador ver ao vivo as câmeras de entrada** (base legal, aviso no local, quantas e quais câmeras, se o morador pode ver à noite/sempre) | em aberto; recurso por câmera, padrão desligado, até 4, auditado, sem gravação |
| D20 | **Como saber o sentido (entrada ou saída)** em cada ponto de acesso (leitor nos dois sentidos? antipassback?) | em aberto; hoje "passagem" quando o ponto não informa |
| D18 | **Onde mora o gabarito facial** (no terminal ou na central), fabricante/modelo do terminal e alternativa sem biometria | em aberto; depende de D2 |
| D1 | **LGPD:** base legal por tratamento, DPO, RIPD, contratos controlador/operador, retenção final, política para dados de visitantes e de gravações | em aberto (jurídico); o lado técnico está em `docs/lgpd.md` |
| D2 | **Fabricante/modelo da primeira controladora** e ambiente de testes de campo | Dahua/Intelbras adotado como primeiro; falta equipamento |
| D3 | **Onde guardar as chaves** (`FIELD_ENCRYPTION_KEYS`, `BLIND_INDEX_KEY`, segredos do agente): KMS/cofre e quem tem acesso; cópia das chaves fora do backup do banco | em aberto |
| D4 | **Custódia da chave privada de lançamento do agente** (offline, quem assina, como recuperar) | em aberto |
| D5 | **Provedores** de e-mail, push e WhatsApp | em aberto |
| D6 | **Infraestrutura de produção:** nuvem/servidor, domínio, TLS, Keycloak próprio ou gerenciado, política de backup | em aberto |
| D7 | **Planos, contratos e limites** por empresa operadora (regras de negócio e cobrança) | em aberto |
| D8 | **Papéis e permissões finos** das telas de administração (o que o síndico e o administrador da empresa podem ver e fazer) | em aberto |
| D9 | Java 26 (não LTS) ou 25 LTS | **decidido: manter Java 26** (2026-10-21) |
| D10 | Licença do projeto | **decidido: Apache 2.0** (trocada de MIT em 2026-10-25; metadados e avisos alinhados) |
| D11 | `.claude/` e `CLAUDE.md` fora do Git | **decidido: só locais** |

### Perguntas objetivas para o jurídico (detalham a D16)
O consentimento do morador é necessário mas **não basta sozinho**. Perguntas a responder por escrito antes de coletar a foto ou criar gabarito facial:
1. **Base legal.** Consentimento (art. 11, I) ou prevenção à fraude e segurança do titular na identificação e autenticação em sistemas eletrônicos (art. 11, II, "g")?
   Se for a segunda, quais limites e quais salvaguardas? Se for consentimento, como garantir que seja **livre** (a entrada não pode depender da biometria)?
2. **Alternativa sem biometria** (cartão, QR): é obrigatória? Que condições mínimas (sem constrangimento, sem prazo maior, sem custo)?
3. **Quem não consentiu:** visitantes, prestadores e dependentes passam pelo terminal ou pela câmera. Como tratar? Menores (art. 14) exigem o responsável.
4. **Condomínio:** é preciso alterar o regulamento interno ou aprovar em assembleia? Quem é o controlador (condomínio ou empresa operadora) e quem é o operador?
5. **Documentos obrigatórios:** RIPD, encarregado (DPO), registro das operações, contrato controlador/operador, aviso de videomonitoramento e texto da tela de transparência.
6. **Retenção:** prazo da foto, do gabarito e do histórico de entradas e saídas; exclusão ao deixar o condomínio.
7. **Histórico de acessos do morador** (rotina e localização): quem pode ver (titular, outros moradores da unidade, síndico, portaria) e em que condições (D17).
8. **Gabarito no terminal ou na central** e o que o fabricante faz com o dado (D18); transferência internacional, se houver.
9. **Incidentes:** procedimento e prazo de comunicação à ANPD e aos titulares em caso de vazamento de foto ou gabarito.
*Enquanto estas perguntas não forem respondidas:* a foto de cadastro e o gabarito facial **não são coletados em produção**; o recurso nasce desligado.

## 7. Auditoria de proveniência e licenças (2026-10-22)
**Medido:** varredura de trechos (janelas normalizadas de 4 linhas e linhas longas) dos 288 arquivos de texto versionados contra os 3 repositórios de
referência e contra o `node-dahua-api` (GPL-3.0): **0 coincidências**; o controle positivo (fork contra original) detectou 2.927, então a ferramenta funciona.
Dependências sem AGPL/SSPL; CNPJs do seed inválidos de propósito; nenhum cabeçalho de copyright de terceiros nos arquivos próprios.

**Aplicado (somente documentação e build):**
- Avisos de licença dentro dos artefatos: `META-INF/domus/` nos jars do agente e do backend (+ transformadores Apache LICENSE/NOTICE no shade do agente) e `/licenses.txt` nos bundles web (`web/scripts/gen-licenses.mjs`).
- Aviso de marcas de terceiros (uso nominativo, sem afiliação, sem conformidade ONVIF/homologação) em `THIRD_PARTY_NOTICES.md`.
- Nota de proveniência/clean-room em `docs/reference-analysis/DAHUA_SOURCES.md` (GPL-3.0 do `node-dahua-api`: nada copiado, nunca adicionar como dependência).
- `docs/reference-analysis/LICENSES.md` reescrito de forma neutra, sem apontar falhas de repositórios de terceiros.

**Resolvido nesta rodada:** L2 (histórico) e, no que cabe ao código, os avisos de licença nos artefatos (L6 parcial: agente completo, backend por conferir).

**Em aberto (decisão do titular ou externo):**
| # | Item | Ação |
|---|---|---|
| L1 | **Marca "Domus"**: nenhuma busca de anterioridade feita | busca no INPI (classes de software/segurança) antes do uso comercial; avaliar nome alternativo |
| ~~L2~~ | ~~Histórico do GitHub continha as skills de terceiros e o `CLAUDE.md`~~ | **RESOLVIDO (2026-10-22):** histórico reescrito e enviado com `force push` (`main` = `88f7a3b`). Resta, se quiser, pedir ao suporte do GitHub a limpeza de objetos órfãos e refazer clones antigos |
| L3 | **Código com assistência de IA**: titularidade e semelhança acidental variam por jurisdição | revisão humana documentada e varredura ampla (ScanCode/FOSSA/Black Duck) antes do lançamento |
| ~~L4~~ | Inventário de licenças | regenerado em 2026-10-22; repetir a cada release (comandos em `docs/inventory/README.md`) |
| L5 | **Grafana (AGPL-3.0)** e **LGPD** | parecer jurídico (ver D1) |
| ~~L6~~ | Texto de licença das dependências Java nos jars | **RESOLVIDO (2026-10-22):** conferido no jar real: 99 de 141 jars trazem licença; os 42 restantes estão listados com licença e página do projeto em `docs/inventory/backend-jars-without-license-files.txt`, empacotado em `META-INF/domus/`. Nenhuma dependência AGPL/GPL-only |

## 8. Como validar localmente
```bash
docker compose up -d postgres
mvn -f backend/pom.xml verify && mvn -f agent/pom.xml test
cd web && npm install && npm test && npm run build
python3 scripts/e2e-browser.py        # precisa de Playwright e do jar do backend
./scripts/e2e-contingency.sh && ./scripts/e2e-agent-update.sh && ./scripts/test-agent-launcher.sh
```
Detalhes e limites de cada prova: [`docs/STATUS.md`](docs/STATUS.md), [`docs/operations.md`](docs/operations.md), [`docs/security.md`](docs/security.md), [`docs/agent.md`](docs/agent.md).

## Auditoria de telas × API (2026-10-25)
Matriz completa em `docs/endpoint-coverage.md` (gerada por `scripts/api-smoke.py --write`; "tem tela" é aproximação por nome de endpoint). Em 2026-10-27 a matriz marca só **2 endpoints sem tela**: `agent/heartbeat` (por desenho) e o contador de não lidas (usado pelo selo, não por uma tela própria). As telas de administração da plataforma e da empresa foram feitas em 2026-10-26 (texto original abaixo, de 2026-10-25).
- **Sem tela na época, a API existia:** empresas operadoras e seus administradores (`companies`), agentes locais (`agents`), rotação de chave de dispositivo, conflitos de sincronização, contador de não lidas.
- **Sem tela por desenho:** `agent/*` (o agente local usa a API direto).
- **Telas de cadastro feitas em 2026-10-25** (aba Cadastros, Gestão): blocos e unidades, moradores por unidade (titular, cônjuge, dependentes; desativar, acesso ao app, exportar e anonimizar), animais, equipe. O morador cuida da própria família e dos animais em "Mais > Minha casa". Escrever exige síndico ou administração; o operador consulta.
- **Câmeras:** a aba cadastra a câmera, mostra online/offline e toca o **vídeo ao vivo** (MediaMTX + HLS). Provado com fonte RTSP de teste, **não com câmera real**; câmera atrás de NAT exige que o agente local publique o fluxo (não feito). Detalhes e limites: `docs/cameras.md`.
- Corrigido: o app do morador tinha 9 abas na barra inferior e não cabia no celular (agora 4 + "Mais"); a mesa da portaria agrupa as abas (Rotina, Segurança, Comunicação, Gestão); a validação de convite e o pedido de autorização de visitante no balcão (sem chamada) têm tela.
