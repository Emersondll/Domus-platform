# Pendências, bugs, melhorias e decisões em aberto

Relatório de 2026-10-22. Fonte de verdade técnica detalhada: [`docs/STATUS.md`](docs/STATUS.md). Este arquivo é o **painel de acompanhamento**:
o que falta, o que está arriscado e o que depende de uma decisão de quem é titular do produto.

> Estado: **não está pronto para produção.** Nenhum equipamento real foi testado, o CI foi corrigido mas não reexecutado, e há decisões jurídicas e de infraestrutura em aberto.

## 1. Evidência do estado atual

| Verificação | Resultado |
|---|---|
| Backend `mvn verify` | 114 testes (28 unitários + 86 de integração com PostgreSQL real), 0 falhas |
| Agente `mvn test` | 36 testes, 0 falhas |
| Web `npm test` + build | 24 testes (19 painel + 5 app do morador), `npm audit` = 0 vulnerabilidades, Node ≥ 22.12 |
| `scripts/e2e-contingency.sh` | OK (queda real da central, offline autoriza 1×, reconciliação sem conflitos) |
| `scripts/e2e-browser.py` | OK (navegador real: morador chama, operador atende, porta só confirma com evento) |
| `scripts/e2e-agent-update.sh` · `scripts/test-agent-launcher.sh` | OK (atualização assinada com jars reais, rollback do launcher) |
| `scripts/verify_keycloak.py` | OK (MFA, PKCE, audiência do token, API com token real) |
| `scripts/restore-drill.sh` | PASS |
| CI no GitHub | **falhou na primeira execução; corrigido localmente e ainda não reexecutado** |

## 2. Melhorias já aplicadas (resumo)
- Plataforma multi-tenant com auditoria imutável, central de operações, app do morador, painel, agente de contingência offline com sincronização idempotente.
- Dispositivos: contrato de adaptadores, simulador, HTTP genérico, ONVIF, relé do agente e **Dahua/Intelbras** (comando + assinatura de eventos), com a regra "aceito ≠ aberto".
- Segurança: Keycloak com MFA por papel, PKCE, **audiência do token**, **renovação silenciosa do token**, limitação de taxa, anti-SSRF/XXE, **criptografia de dados pessoais em nível de campo** (AES-256-GCM, índice cego para placa, rotação e migração), **armazenamento do agente cifrado em repouso**, **atualização segura do agente** (Ed25519, anti-retrocesso, rollback).
- Operação: métricas, logs JSON, tracing opcional, backup com drill, stack de produção, **tema Domus** nos apps e no login do Keycloak.
- Código: pacote raiz `br.com.domus.platform`; domínios divididos em `controller`/`service`/`repository`/`entity` ([ADR 0007](docs/adr/0007-organizacao-de-pacotes.md)).

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
| B1 | **CI não reexecutado** após as correções; o CodeQL com Java 26 nunca foi validado | o pipeline pode continuar vermelho | rodar no GitHub e corrigir o que aparecer |
| B2 | **Desempenho após a criptografia de campos não foi isolado**: o teste de carga deu ~900–1.500 req/s (antes 3.000+), inclusive em endpoint sem dado pessoal, com a máquina carregada | pode haver custo real da cifra ou ruído de ambiente | repetir `scripts/load-test.py` em máquina limpa, com e sem cifra |
| B3 | **Placas passaram a ser listadas por data de cadastro** (a ordenação por placa cifrada seria aleatória) | mudança de comportamento da API | ordenar no cliente ou criar coluna de ordenação não sensível, se for necessário |
| B4 | **E-mail do usuário deixou de ser único** e não há busca nem ordenação por nome/e-mail | provisionamento depende só do `sub` do provedor | aceitável; documentado |
| B5 | **Trocar `BLIND_INDEX_KEY` quebra a unicidade das placas** já cadastradas | duplicidade silenciosa | não trocar; se precisar, procedimento de reindexação |
| B6 | Suposições **não confirmadas** do Dahua/Intelbras: campos `Status`/`UserID` do evento, parâmetro `heartbeat`, `openDoor` em controladoras (só visto em VTO) | a confirmação de abertura pode nunca chegar (o comando termina `UNKNOWN`, falha segura) | roteiro de campo em `docs/reference-analysis/DAHUA_SOURCES.md` |
| B7 | Cada instância da API abre a própria conexão de eventos Dahua | conexões duplicadas com mais de uma instância | eleição de líder |
| B8 | Revogação de credencial durante a queda só chega ao agente quando a política renova (TTL 6 h) | janela limitada e auditável | risco aceito |
| B9 | Quota offline é por agente: dois agentes offline podem estourar uma credencial multiuso | vira `QUOTA_EXCEEDED` na volta | risco aceito |
| B10 | Em Keycloak já existente, o mapper de audiência precisa ser adicionado, senão os logins recebem 401 | falha no upgrade | passo documentado em `docs/operations.md` |

## 4. Pendências técnicas que podem ser feitas sem definição externa (em ordem sugerida)
1. **Validar o CI** (B1) e corrigir o que aparecer.
2. **Medir o desempenho em máquina limpa** (B2).
3. **Login OIDC e renovação do token em navegador contra o Keycloak real** (hoje o E2E de navegador usa o login de desenvolvimento; PKCE e API já são provados por `verify_keycloak.py`).
4. **Telas de administração** para administrador da plataforma, da empresa e síndico (hoje só existe API).
5. **Escala horizontal:** limitador de taxa compartilhado (Redis), lock distribuído nos agendamentos, eleição de líder na assinatura Dahua.
6. **Mais segurança:** mTLS por agente/dispositivo, rotação da chave de lançamento do agente, chaves de criptografia em gerenciador de segredos (KMS) em vez de variável de ambiente.
7. **Relatórios** por condomínio e exportação.
8. **Agente:** pacote `.deb` e suporte além de Linux/systemd.
9. Testes de integração organizados por domínio (hoje ficam todos em `br.com.domus.platform`).

## 5. Pendências que dependem de equipamento, contrato ou terceiros
- **Hardware real:** testar o `DAHUA_CGI` com uma controladora; obter a documentação oficial do modelo exato (NDA/revendedor); teste com perda de conectividade no meio do comando.
- **SIP/telefonia e vídeo ao vivo (RTSP):** exigem servidor SIP/mídia e câmeras.
- **Notificações push, e-mail e WhatsApp:** exigem provedor e credenciais (WhatsApp, API oficial).
- **Infraestrutura de produção:** réplica e PITR do PostgreSQL, WAF/DDoS, criptografia de disco/banco, varredura de imagens, pentest independente, medição de RTO/RPO.
- **Grafana (AGPL-3.0)** como serviço separado: revisão jurídica se o produto for oferecido a terceiros como serviço.
- **App nativo (React Native):** só se houver necessidade de push nativo, NFC ou lojas.

## 6. Decisões em aberto (do titular do produto)
| # | Decisão | Situação |
|---|---|---|
| D1 | **LGPD:** base legal por tratamento, DPO, RIPD, contratos controlador/operador, retenção final, política para dados de visitantes e de gravações | em aberto (jurídico); o lado técnico está em `docs/lgpd.md` |
| D2 | **Fabricante/modelo da primeira controladora** e ambiente de testes de campo | Dahua/Intelbras adotado como primeiro; falta equipamento |
| D3 | **Onde guardar as chaves** (`FIELD_ENCRYPTION_KEYS`, `BLIND_INDEX_KEY`, segredos do agente): KMS/cofre e quem tem acesso; cópia das chaves fora do backup do banco | em aberto |
| D4 | **Custódia da chave privada de lançamento do agente** (offline, quem assina, como recuperar) | em aberto |
| D5 | **Provedores** de e-mail, push e WhatsApp | em aberto |
| D6 | **Infraestrutura de produção:** nuvem/servidor, domínio, TLS, Keycloak próprio ou gerenciado, política de backup | em aberto |
| D7 | **Planos, contratos e limites** por empresa operadora (regras de negócio e cobrança) | em aberto |
| D8 | **Papéis e permissões finos** das telas de administração (o que o síndico e o administrador da empresa podem ver e fazer) | em aberto |
| D9 | Java 26 (não LTS) ou 25 LTS | **decidido: manter Java 26** (2026-10-21) |
| D10 | Licença do projeto | **decidido: MIT** |
| D11 | `.claude/` e `CLAUDE.md` fora do Git | **decidido: só locais** |

## 7. Como validar localmente
```bash
docker compose up -d postgres
mvn -f backend/pom.xml verify && mvn -f agent/pom.xml test
cd web && npm install && npm test && npm run build
python3 scripts/e2e-browser.py        # precisa de Playwright e do jar do backend
./scripts/e2e-contingency.sh && ./scripts/e2e-agent-update.sh && ./scripts/test-agent-launcher.sh
```
Detalhes e limites de cada prova: [`docs/STATUS.md`](docs/STATUS.md), [`docs/operations.md`](docs/operations.md), [`docs/security.md`](docs/security.md), [`docs/agent.md`](docs/agent.md).
