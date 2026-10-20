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
   Keycloak real (`scripts/verify_keycloak.py`: verifier errado → 400, código reutilizado → 400, token aceito pela API). Falta um teste
   automatizado em navegador (Playwright) e refresh de token (hoje expira em 5 min → novo login transparente pelo SSO).
2. **Validação de audiência (`aud`)** do token não está ativada: qualquer token do realm assinado pelo Keycloak é aceito.
3. **mTLS por dispositivo/agente** não implementado (hoje: chave por agente/dispositivo sobre TLS).
4. **Nada testado com hardware real** (controladoras, câmeras, SIP, vídeo).
5. Dados em repouso: SQLite do agente e campos pessoais no PostgreSQL **sem criptografia de campo** (use disco cifrado).
6. Sem WAF/proteção DDoS; sem teste de intrusão independente; sem varredura de imagem de contêiner.
7. Limitação de taxa e agendamentos por instância (ver [operations.md](operations.md)).
8. **Devtools de teste** (`vitest`/`vite` aninhado) com avisos de vulnerabilidade só na cadeia de teste/desenvolvimento (0 em
   produção). `vitest 5` exige Node ≥ 22.12; atualizar junto com o Node do CI/dev.
9. O CI foi escrito mas **não executado** no GitHub Actions.
