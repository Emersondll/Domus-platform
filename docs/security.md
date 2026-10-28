# Segurança — modelo, controles e lacunas

## O que protege o quê (e onde está testado)
| Risco | Controle | Evidência |
|---|---|---|
| Acesso entre condomínios/empresas (IDOR/BOLA) | tenant derivado das associações; recurso fora do escopo = 404; FKs compostas no banco | `TenantIsolationIT`, `MemberProvisioningIT`, `AgentSyncIT` |
| Elevação de privilégio | papéis só em `membership` (nunca no token); `PLATFORM_ADMIN` não lê dados de moradores; operador só lê/opera | `TenantIsolationIT` |
| Reuso de QR Code | token sem dado pessoal, HMAC amarrado ao condomínio, uso consumido por UPDATE atômico | `CondominialOperationsIT` (20 validações simultâneas → 1 autorização) |
| Abrir porta sem base ou sem confirmação | base obrigatória (atendimento do operador ou validação recente), justificativa, idempotência, só `CONFIRMED` por evento | `DeviceCommandsIT`, `AgentSyncIT` |
| SSRF por URL de dispositivo | lista permitida de hosts (`DEVICE_HTTP_ALLOWED_HOSTS`, vazia = nenhum) | `DeviceCommandsIT` |
| XXE (ONVIF) | DTD desabilitado | `OnvifAdapterTest` |
| Força bruta em credenciais de máquina | bloqueio do IP após 10 falhas/5 min; limite de taxa por chave | `RateLimitIT` |
| Força bruta em login de pessoas / MFA | Keycloak: bloqueio progressivo, política de senha (12+), TOTP obrigatório para papéis de equipe | `scripts/verify_keycloak.py` (login real: operador → configuração de OTP; morador → código) |
| Política/agente falsos | política assinada (HMAC), versionada, com validade; rejeita adulterada/outro agente/antiga | `ContingencyTest` |
| Adulteração da auditoria | `audit_log` append-only por trigger (mantido após restauração) | `TenantIsolationIT`, `restore-drill.sh` |
| Vazamento por logs | sem tokens/senhas em logs; correlação por ID | revisão de código (sem teste automatizado) |
| Porta de gestão exposta | `:8082` fora do proxy; Caddy bloqueia `/actuator`, `/dev`, `/swagger-ui`, `/v3/api-docs` | verificado na stack de produção local |
| Contêiner | usuário não-root; sem segredos na imagem | `backend/Dockerfile` |

CSRF: a API é stateless por Bearer (sem cookies de sessão), por isso o CSRF está desligado de propósito. XSS: o painel/app
renderizam texto via React (escape por padrão) e há CSP restritiva no proxy. Injeção SQL: JPA/parâmetros nomeados; consultas nativas parametrizadas.

## Lacunas conhecidas (não declare "pronto para produção" sem tratá-las)
1. **Login OIDC dos frontends implementado, mas não exercitado em navegador real.** O painel e o app do morador usam Authorization Code +
   PKCE (S256) com `state`, token só em memória e configuração por `/config.json` do proxy; o login de desenvolvimento não vai para o bundle
   de produção (verificado). Validado: vetor da RFC 7636, `state` forjado/reuso recusados (testes de UI) e o protocolo completo contra o
   Keycloak real (`scripts/verify_keycloak.py`: verifier errado → 400, código reutilizado → 400, token aceito pela API). A renovação
   silenciosa do token está implementada nos dois apps. Falta exercitar o login OIDC num navegador contra o Keycloak real (o E2E de navegador usa o login de desenvolvimento).
2. **Validação de audiência (`aud=portaria-api`)**: implementada e provada com Keycloak real. Em Keycloak já existente é preciso adicionar o mapper de audiência (`docs/operations.md`).
3. **mTLS por dispositivo/agente** não implementado (hoje: chave por agente/dispositivo sobre TLS).
4. **Nada testado com hardware real** (controladoras, câmeras, SIP). O vídeo ao vivo foi provado só com fonte RTSP de teste (`docs/cameras.md`).
5. Dados em repouso: campos pessoais no PostgreSQL e o SQLite do agente têm **criptografia de campo/arquivo** (AES-256-GCM); falta criptografia do disco/banco como um todo e guardar as chaves em KMS/cofre (D3).
6. Sem WAF/proteção DDoS; sem teste de intrusão independente; sem varredura de imagem de contêiner.
7. Limitação de taxa e agendamentos por instância (ver [operations.md](operations.md)).
8. Front-end exige Node ≥ 22.12; `npm audit` completo = 0 vulnerabilidades (revalidar a cada atualização de dependências).
9. CI no GitHub Actions: 7 jobs verdes desde 2026-10-22 e nos pushes seguintes.
10. **Vídeo ao vivo:** o endereço RTSP e a senha da câmera nunca vão ao navegador; a sessão usa token HMAC de 10 min preso ao caminho da câmera, validado pelo servidor de mídia em `/internal/media/auth` (bloqueado no proxy e restrito por IP). O MediaMTX precisa de TLS na frente e de `hlsAllowOrigin` restrito ao painel em produção.
11. **"Meus acessos" e foto de cadastro:** o histórico pessoal de entradas e saídas é lido só pelo titular (outro morador da mesma unidade, equipe e outro condomínio recebem 404), cada consulta é auditada e a referência do cartão ou usuário fica cifrada com índice cego. A foto é cifrada (AES-GCM), sem URL pública, `no-store`, e o consentimento é registrado e revogável. **Limites:** o morador pode capturar a tela; sem detecção de rosto (a qualidade depende de quem envia); o evento do leitor é autenticado pela chave do dispositivo, então um dispositivo comprometido pode forjar passagens do próprio ponto de acesso; os campos do evento Dahua não foram confirmados em campo (B6).

## Varredura das imagens de contêiner (2026-10-27)
Trivy 0.58 (`--severity HIGH,CRITICAL --scanners vuln`), imagens baixadas no mesmo dia. **Achados reais; nenhum foi corrigido por atualização de versão** (cada troca exige reteste da pilha):

| Imagem | CRITICAL | HIGH | Origem principal |
|---|---|---|---|
| `eclipse-temurin:26-jre` (backend) | 0 | 0 | — |
| `postgres:17` | 2 | 86 | `gosu` compilado com Go 1.24 (a imagem oficial); 1 CRITICAL e 25 HIGH têm correção |
| `caddy:2` | 0 | 10 | Alpine (`curl`, `brotli`) e `go-jose`; 9 têm correção |
| `quay.io/keycloak/keycloak:26.4` | 8 | 82 | pacotes do RHEL 9 e OpenJDK 21; quase todos com correção em um 26.x mais novo |
| `bluenviron/mediamtx:1.15.1` | 1 | 45 | bibliotecas Go (`quic-go`, `x/crypto`); todos com correção em versão mais nova |
| `prom/prometheus:v3.5.0` | 4 | 108 | bibliotecas Go; a maioria com correção |
| `grafana/grafana:12.2.0` | 9 | 148 | Alpine e bibliotecas Go; a maioria com correção |

**Leitura honesta:** vulnerabilidade listada não é vulnerabilidade explorável (o `gosu`, por exemplo, não recebe entrada externa), mas as imagens de Keycloak, MediaMTX, Prometheus e Grafana estão **defasadas** e devem subir de versão antes de produção, com reteste (`verify_keycloak.py`, `e2e-oidc-browser.py`, `e2e-browser.py` com vídeo). Quem expõe porta é o Caddy, o Keycloak e o MediaMTX: priorizar esses três. O Dependabot (`docker`, `docker-compose`) abre os pedidos de atualização.
**Atualizações aplicadas em 2026-10-28 (pedidos do Dependabot):** Keycloak 26.4 → 26.8.1 (dev e produção; `verify_keycloak.py` e `e2e-oidc-browser.py` com 15 OK), Spring Boot 4.0.9 → 4.1.2 (`mvn verify` 31 + 151, 0 falhas; `load-test` com 0 erros nos 6 cenários), atualizações menores do agente e do web e as ações do GitHub (gitleaks v3 em Node 24, setup-java 6, setup-node 7, upload-artifact 7). **Não aplicadas:** Grafana 12 → 13 (versão maior, AGPL, painéis provisionados sem teste), Prometheus 3.5 → 3.15 (só na observabilidade; sem teste) e Postgres 17 → 18 (versão maior: troca o formato do diretório de dados; exige migração planejada). Estas continuam defasadas.
**Digests fixados** (`tag@sha256`) nas tags móveis: `postgres:17`, `caddy:2` e `eclipse-temurin:26-jre`; o Dependabot atualiza o digest. As tags já versionadas (Keycloak, MediaMTX, Prometheus, Grafana) não foram fixadas por digest.
