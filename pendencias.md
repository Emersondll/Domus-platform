# Pendências, bugs, melhorias e decisões em aberto

Atualizado em 2026-10-26. Fonte de verdade técnica detalhada: [`docs/STATUS.md`](docs/STATUS.md). Este arquivo é o **painel de acompanhamento**:
o que falta, o que está arriscado e o que depende de uma decisão de quem é titular do produto.

> Estado: **não está pronto para produção.** Nenhum equipamento real foi testado, e há decisões jurídicas e de infraestrutura em aberto.

## 1. Evidência do estado atual

| Verificação | Resultado |
|---|---|
| Backend `mvn verify` | 152 testes (30 unitários + 122 de integração com PostgreSQL real), 0 falhas |
| Agente `mvn test` | 36 testes, 0 falhas |
| Web `npm test` + build | 68 testes (43 painel + 25 app do morador), `npm audit` = 0 vulnerabilidades, Node ≥ 22.12 |
| `scripts/e2e-contingency.sh` | OK (queda real da central, offline autoriza 1×, reconciliação sem conflitos) |
| `scripts/e2e-browser.py` | OK, 40 verificações (navegador real: chamada, pânico com janela de alerta, porta só confirma com evento, visitantes, encomendas, mensagens, cadastros, vídeo ao vivo) |
| `scripts/e2e-agent-update.sh` · `scripts/test-agent-launcher.sh` | OK (atualização assinada com jars reais, rollback do launcher) |
| `scripts/verify_keycloak.py` | OK (MFA, PKCE, audiência do token, API com token real) |
| `scripts/restore-drill.sh` | PASS (16 migrações, V1–V16) |
| `scripts/api-smoke.py` | OK: 136 endpoints; 45 GETs de condomínio × 4 papéis sem 5xx nem vazamento entre empresas |
| CI no GitHub | **verde** nos pushes até `76953cc` (2026-10-26); os commits locais mais novos ainda não foram enviados |

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
| B3 | **Placas passaram a ser listadas por data de cadastro** (a ordenação por placa cifrada seria aleatória) | mudança de comportamento da API | ordenar no cliente ou criar coluna de ordenação não sensível, se for necessário |
| B4 | **E-mail do usuário deixou de ser único** e não há busca nem ordenação por nome/e-mail | provisionamento depende só do `sub` do provedor | aceitável; documentado |
| B5 | **Trocar `BLIND_INDEX_KEY` quebra a unicidade das placas** já cadastradas | duplicidade silenciosa | não trocar; se precisar, procedimento de reindexação |
| B6 | Suposições **não confirmadas** do Dahua/Intelbras: campos `Status`/`UserID` do evento, parâmetro `heartbeat`, `openDoor` em controladoras (só visto em VTO) | a confirmação de abertura pode nunca chegar (o comando termina `UNKNOWN`, falha segura) | roteiro de campo em `docs/reference-analysis/DAHUA_SOURCES.md` |
| B7 | Cada instância da API abre a própria conexão de eventos Dahua | conexões duplicadas com mais de uma instância | eleição de líder |
| B8 | Revogação de credencial durante a queda só chega ao agente quando a política renova (TTL 6 h) | janela limitada e auditável | risco aceito |
| B9 | Quota offline é por agente: dois agentes offline podem estourar uma credencial multiuso | vira `QUOTA_EXCEEDED` na volta | risco aceito |
| B10 | Em Keycloak já existente, o mapper de audiência precisa ser adicionado, senão os logins recebem 401 | falha no upgrade | passo documentado em `docs/operations.md` |

## 4. Próximas ações (ordem sugerida)
**A. "Meus acessos" do morador, com a foto do cadastro, preparando a identificação facial — PRÓXIMA AÇÃO (pedido de 2026-10-26).**
O morador vê os **horários de entrada e saída** dele no condomínio e a **foto do cadastro**. A foto existe porque, no futuro, a entrada será por
**identificação facial**. Escopo, em etapas (cada uma com testes, E2E e documentação; recurso novo vem **desligado**):
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

**B. Demais pendências técnicas que podem ser feitas sem definição externa (em ordem sugerida)**
1. ~~Validar o CI (B1)~~ e ~~medir o desempenho (B2)~~: feitos em 2026-10-22.
2. **Telas de câmeras e imagem da entrada junto do pedido de visita — FEITO com câmera simulada (2026-10-26):** grade, tela cheia, editar/desativar, câmera de entrada, captura com prévia, imagem cifrada, retenção e visão no app. Ver [`docs/plano-cameras-visitas.md`](docs/plano-cameras-visitas.md). **Falta:** câmera real (captura HTTP/ONVIF e compatibilidade de codec), D12–D15 formalizadas (o recurso vem **desligado** por padrão) e o parecer jurídico D13.
3. **Agente local publicar o vídeo das câmeras** no MediaMTX (câmera atrás de NAT) e ONVIF `GetStreamUri`; hoje o vídeo só funciona com RTSP alcançável pela central (`docs/cameras.md`).
4. **Login OIDC e renovação do token em navegador contra o Keycloak real** (hoje o E2E de navegador usa o login de desenvolvimento; PKCE e API já são provados por `verify_keycloak.py`).
5. **Telas de administração da plataforma e da empresa operadora — PENDENTE (só existe API).** Escopo:
   - **Administrador da plataforma:** cadastrar e listar empresas operadoras (`/companies`) e os administradores de cada empresa (`/companies/{id}/admins`); criar condomínios; visão de todas as empresas.
   - **Administrador da empresa operadora:** criar e gerenciar os condomínios da empresa, operadores e síndicos (hoje só a equipe de um condomínio, na aba Equipe), agentes locais (`/condominiums/{id}/agents`: cadastro e chave), rotação de chave de dispositivo (`rotate-key`) e conflitos de sincronização (`/sync-conflicts`).
   - **Antes de começar, decidir D8** (o que cada papel pode ver e fazer) e D7 (planos e limites por empresa). As telas do síndico/portaria (cadastros) foram feitas em 2026-10-25.
6. **Escala horizontal:** limitador de taxa compartilhado (Redis), lock distribuído nos agendamentos, eleição de líder na assinatura Dahua.
7. **Mais segurança:** mTLS por agente/dispositivo, rotação da chave de lançamento do agente, chaves de criptografia em gerenciador de segredos (KMS) em vez de variável de ambiente.
8. ~~Relatórios por condomínio e exportação~~: feitos (relatório de acessos com CSV, Lote B). Faltam relatórios de encomendas, ocorrências e turnos.
9. **Qualidade:** teste de carga (`scripts/load-test.py`) não foi reexecutado depois das últimas rodadas; PWA do morador sem teste em celular real; sirene do alerta de pânico não foi ouvida em navegador real (só provada por teste que os tons iniciam e param).
10. **Retenção de mensagens** individuais (flagrado em `docs/lgpd.md`) e saída automática de visitante esquecido no local (hoje só aviso após 12 h).
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
Matriz completa em `docs/endpoint-coverage.md` (gerada por `scripts/api-smoke.py --write`; "tem tela" é aproximação por nome de endpoint). A API tem 136 endpoints; **11 não têm tela**:
- **Sem tela, a API existe:** empresas operadoras e seus administradores (`companies`), agentes locais (`agents`), rotação de chave de dispositivo, conflitos de sincronização, contador de não lidas. É a administração da plataforma/empresa, ainda só pela API.
- **Sem tela por desenho:** `agent/*` (o agente local usa a API direto).
- **Telas de cadastro feitas em 2026-10-25** (aba Cadastros, Gestão): blocos e unidades, moradores por unidade (titular, cônjuge, dependentes; desativar, acesso ao app, exportar e anonimizar), animais, equipe. O morador cuida da própria família e dos animais em "Mais > Minha casa". Escrever exige síndico ou administração; o operador consulta.
- **Câmeras:** a aba cadastra a câmera, mostra online/offline e toca o **vídeo ao vivo** (MediaMTX + HLS). Provado com fonte RTSP de teste, **não com câmera real**; câmera atrás de NAT exige que o agente local publique o fluxo (não feito). Detalhes e limites: `docs/cameras.md`.
- Corrigido: o app do morador tinha 9 abas na barra inferior e não cabia no celular (agora 4 + "Mais"); a mesa da portaria agrupa as abas (Rotina, Segurança, Comunicação, Gestão); a validação de convite e o pedido de autorização de visitante no balcão (sem chamada) têm tela.
