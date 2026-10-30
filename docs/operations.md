# Operação

## Implantação
1. Gere os artefatos: `mvn -f backend/pom.xml package -DskipTests` e `npm --prefix web run build` (o CI faz o mesmo).
2. `cp deploy/.env.prod.example deploy/.env.prod` e preencha **todos** os segredos (`openssl rand -base64 48`). Sem eles a stack
   não sobe (variáveis obrigatórias). O arquivo real nunca é versionado.
3. `docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build`
4. DNS: `api.`, `panel.`, `app.` e `auth.` apontando para o servidor. O Caddy obtém os certificados (Let's Encrypt) sozinho.
   Os apps web leem `/config.json` (gerado pelo Caddy a partir de `DOMAIN`): o **mesmo build** serve qualquer ambiente.
   Os clientes `portaria-panel` e `portaria-resident` do realm aceitam `https://panel.*/*` e `https://app.*/*`; restrinja ao seu domínio.
5. **Primeiro administrador:** crie o usuário no Keycloak (`https://auth.<domínio>`, usando o admin criado em
   `KEYCLOAK_ADMIN`), atribua o papel de realm `platform-admin`, copie o `id` do usuário para
   `BOOTSTRAP_PLATFORM_ADMIN_SUBJECT`/`..._EMAIL`, reinicie `app` uma vez e **remova essas variáveis** em seguida.
6. A partir daí tudo é feito pela API: empresas → condomínios → membros/operadores → moradores → agentes → dispositivos.
   O console de administração do Keycloak **não** é publicado pelo proxy; acesse por túnel/VPN.

Validado localmente (com `DOMAIN=localhost`): TLS e cabeçalhos de segurança, `/actuator`, `/dev`, `/swagger-ui` e
`/v3/api-docs` retornam 404 no proxy, porta de gestão inacessível de fora, login via Keycloak e API aceitando o token.
**Não** validado: certificados públicos reais (Let's Encrypt), múltiplas instâncias da API, DNS real.

## Antes de atualizar para as migrações V24/V25 (aviso ao síndico)
As migrações **V24** (cadastro de morador inativo) e **V25** (cadastro de dependente) **desativam vínculos de acesso ao app** que já existam nessas condições, sem apagar nada.
Em um ambiente com moradores reais, antes de aplicar: (1) liste quem será afetado (`SELECT m.id FROM membership m JOIN resident r ON r.id = m.resident_id WHERE m.active AND
(r.resident_type = 'DEPENDENT' OR NOT r.active)`); (2) avise o síndico de que essas pessoas deixam de entrar no app (só adultos usam o app; dependentes ficam no cadastro); (3) se
algum for adulto cadastrado como dependente por engano, corrija o tipo e conceda o acesso de novo depois da migração. A reversão é conceder o acesso outra vez pela tela de Moradores.

## Suspensão de empresa e de condomínio
Suspender desliga o app para todos do escopo (403 "Access suspended") e recusa o agente e os equipamentos; nada é apagado. Reativar devolve o acesso. Quem suspende: a empresa, só o
administrador da plataforma; o condomínio, o administrador da plataforma ou o da empresa dona (Administração → Empresas/Condomínios). Fica na auditoria (`company.suspend`,
`condominium.suspend` e os de reativar).

## Variáveis de ambiente (API)
| Variável | Obrigatória em produção | Para quê |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | sim | PostgreSQL |
| `OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI` | sim | valida o token (`iss`) e busca as chaves pela rede interna |
| `CREDENTIAL_HMAC_SECRET` (≥32) | sim | assinatura dos QR Codes |
| `AGENT_POLICY_MASTER_SECRET` (≥32) | sim | deriva o segredo de assinatura da política de cada agente |
| `FIELD_ENCRYPTION_KEYS` (`id:base64`, 32 bytes) · `BLIND_INDEX_KEY` (≥32) | sim | criptografia de dados pessoais (ver abaixo) |
| `FIELD_ENCRYPTION_ACTIVE_KEY_ID` · `FIELD_ENCRYPTION_MIGRATE` | não | chave ativa (padrão: a primeira) · cifra dados legados/rotaciona na partida |
| `DEVICE_HTTP_ALLOWED_HOSTS` | se usar controladora HTTP | anti-SSRF: hosts que a central pode contatar |
| `BOOTSTRAP_PLATFORM_ADMIN_*` | só na instalação | primeiro administrador |
| `DB_POOL_SIZE` (20) · `TRUST_FORWARDED_FOR` (true no prod) · `MANAGEMENT_PORT` (8082) | não | ajuste |
| `OTEL_TRACES_EXPORT_ENABLED`, `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `TRACING_SAMPLE_PROBABILITY` | não | exporta traces (desligado por padrão) |
Trocar `CREDENTIAL_HMAC_SECRET` invalida todos os convites/QR já emitidos; trocar `AGENT_POLICY_MASTER_SECRET` exige
recadastrar os agentes (o segredo de política de cada um é derivado dele).

## Observabilidade
- Logs JSON (perfil `prod`) com `correlationId` (`X-Correlation-Id` entra e sai) e `trace_id` quando o tracing está ativo.
- Métricas Prometheus em `:8082/actuator/prometheus` (rede interna). Subir Prometheus + Grafana:
  `docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d` (dev) ou `--profile observability` (prod).
  Dashboard "Domus — operação" e 9 regras de alerta provisionados (validados: alvo `up`, regras sem erro, dashboard com 12 painéis).
- Health: `/actuator/health/liveness` e `/readiness` na porta 8082.

| Alerta | Significado | Primeira ação |
|---|---|---|
| `PortariaAgentOffline` | agente sem contato > 5 min: condomínio provavelmente em contingência | verificar internet/energia no local; o agente segue aceitando QR pela política até ela vencer (6 h) |
| `PortariaUnsyncedEvents` | eventos acumulados no agente > 15 min | conferir conectividade/credenciais do agente; os eventos não se perdem |
| `PortariaSyncConflictsOpen` | reconciliação achou divergência | revisar em `/sync-conflicts` e resolver |
| `PortariaDoorCommandUnknown` | comando de porta sem confirmação | **ir ao local**: não se sabe se a porta abriu |
| `PortariaDeviceOffline` | dispositivo offline | checar rede/energia do equipamento |
| `PortariaCallQueueGrowing` | fila > 5 por 3 min | acionar mais operadores |
| `PortariaHigh5xxRate`, `PortariaHighLatencyP95`, `PortariaApiDown` | saúde da API | logs por `correlationId`; ver pool do banco |

## Backup, restauração e recuperação de desastre
- `scripts/backup.sh` — `pg_dump` (formato custom) + SHA-256, permissões 600, retenção local (14) e criptografia GPG opcional
  (`BACKUP_GPG_RECIPIENT`). Agende (ex.: cron de hora em hora + diário offsite) e **copie para fora do servidor**.
- `scripts/restore.sh <dump> <banco_novo>` — restaura sempre em um banco **novo** (nunca sobrescreve o em uso).
- `scripts/restore-drill.sh` — exercício completo: backup → restauração em banco temporário → compara contagens de 16 tabelas,
  constraints, índices e triggers → confere que `audit_log` continua append-only. **Resultado da última execução: PASS**
  (restauração em 1 s com a base de desenvolvimento; tempos reais dependem do volume). Rode mensalmente e após mudar o esquema.
- Procedimento de desastre: (1) subir PostgreSQL novo; (2) `restore.sh` do último backup válido; (3) apontar `DB_URL` para o banco
  restaurado e subir `app`; (4) confirmar `/readiness` e rodar `restore-drill`/consultas de sanidade; (5) agentes locais
  reenviam os eventos pendentes sozinhos (idempotência), então eventos ocorridos após o backup, offline, são recuperados.
  **RPO** = intervalo entre backups (para RPO menor, habilite arquivamento WAL/PITR — não configurado). **RTO** depende do
  tamanho do banco e do provisionamento; **não medido em produção**.
- Backup do Keycloak: seu banco (`keycloak`) está no mesmo PostgreSQL e entra no backup se `PGDB` for ajustado (rode o script
  também com `PGDB=keycloak`); a definição do realm está versionada em `deploy/keycloak/portaria-realm.json`.

## Associação de moradores e ticket de saída (V26, V27)

- **Código de adesão:** o síndico gera em Cadastros → Solicitações. Quem o tem só consegue **abrir um pedido**; o acesso nasce quando o síndico aprova. Trocar ou desligar o código vale na hora (pedidos já abertos continuam pendentes). Para o morador conseguir pedir, ele precisa ter conta no provedor de identidade: a política de auto-cadastro do Keycloak em produção é decisão de implantação (D6).
- **Ticket de saída:** numeração por condomínio feita por gatilho no banco; o número nunca é reaproveitado. Entradas anteriores à V27 não têm número.

## Rotação de segredos e chaves
- **Agente:** `java -jar agent.jar --rotate-key` (a chave antiga vale 24 h até a nova ser usada); revogar: `POST .../agents/{id}/revoke`.
- **Dispositivo (ingestão de eventos):** `POST .../devices/{id}/rotate-key`.
- **Segredos da API:** ver tabela acima (impacto da troca). **Keycloak:** troca de chaves do realm pelo console (via túnel).

## Retenção
Expurgo diário às 03:30 (`portaria.retention.*`, ver [lgpd.md](lgpd.md)). Métrica `portaria_retention_deleted_total`.

## Capacidade (medido)
`scripts/load-test.py`, 8 vCPU, API + PostgreSQL + gerador na **mesma máquina** (cliente Python): vazão de leitura
~3.000–3.700 req/s e escrita (validação de credencial) ~1.900–2.300 req/s com 20–50 clientes simultâneos; p95 entre 16 ms e 54 ms,
0 erros, 0 timeouts do pool. É um **piso**, não a capacidade de produção, e não cobre múltiplas instâncias, rede real nem
volumes grandes. O teste revelou e fez corrigir um deadlock de pool na auditoria (ver commit `fix: stop audit logging…`).
Dimensionamento do pool: `DB_POOL_SIZE` (padrão 20) + 3 conexões do pool de auditoria.

## Limites operacionais conhecidos
- Limitação de taxa em memória (por instância); com várias instâncias use armazenamento compartilhado (ex.: Redis).
- `@Scheduled` (expurgo, vencimento de comandos, métricas) roda em cada instância: com N instâncias, use lock distribuído
  (ex.: ShedLock) — as operações são idempotentes, mas não otimizadas para isso.
- Sem alta disponibilidade do PostgreSQL configurada (réplica/failover).

## Cadastrar uma controladora Dahua/Intelbras (`DAHUA_CGI`)
> Não validado com hardware real: faça o primeiro teste com a porta supervisionada.

1. Crie no equipamento um usuário só com a permissão de abrir a porta (o protocolo usa Digest/MD5; mantenha o tráfego em rede privada ou VPN).
2. Exporte as credenciais como variáveis de ambiente da aplicação (ex.: `PORTARIA_GATE1_USER`, `PORTARIA_GATE1_PASS`); o banco guarda só os nomes.
3. Inclua o IP em `portaria.devices.http-allowed-hosts` (proteção SSRF).
4. Cadastre o dispositivo com `adapter=DAHUA_CGI` e `config` = `{"baseUrl":"http://10.0.0.20","channel":"1","userEnv":"PORTARIA_GATE1_USER","passwordEnv":"PORTARIA_GATE1_PASS"}`.
5. "OK" na resposta significa só **aceito**. A central mantém uma conexão de eventos por equipamento (`eventManager.cgi`; desligue com `"events":"off"` na config, ou
   globalmente com `portaria.devices.dahua-events.enabled=false`). Só um evento que devolva `UserID=portaria-<commandId>` confirma o comando; sem isso ele fica `SENT` e termina `UNKNOWN`.
   Opcional: `"eventCodes":"AccessControl,DoorStatus"`.
6. Antes de produção, execute o roteiro de campo de `docs/reference-analysis/DAHUA_SOURCES.md`.

## Identidade visual
Os dois apps compartilham `web/shared/src/theme.css`. O login do Keycloak usa o tema `domus` (`deploy/keycloak/themes/domus/login`, montado em
`/opt/keycloak/themes` nos dois compose; o realm define `loginTheme`, e o idioma padrão é pt-BR). Fora do tema: o Swagger UI (só em dev, desligado
em produção), o console de administração do Keycloak e o console de conta do usuário continuam com a aparência padrão.

## Audiência do token (`aud`)
A API em produção exige `aud=portaria-api` (variável `OIDC_AUDIENCE`). O realm importado já tem o mapper de audiência nos clientes `portaria-panel` e `portaria-resident`; em um Keycloak já existente, adicione o mapper "Audience" (audiência customizada `portaria-api`, só no access token) a cada cliente, ou os logins passarão a receber 401.

## Criptografia de dados pessoais
Nome, e-mail, telefone (morador, usuário, visitante, chamador) e placa são cifrados na aplicação com AES-256-GCM (`FieldCipher`); no banco ficam como
`enc:v1:<chave>:<dados>`. A unicidade da placa e a do e-mail da conta usam um índice cego (HMAC com `BLIND_INDEX_KEY`), que também permite busca por
igualdade: `GET /condominiums/{id}/vehicles?plate=ABC1D23` e `GET /condominiums/{id}/members?email=...`. Não há busca parcial nem por nome. Ordenar
veículos por placa (`?sort=plate,asc`) é feito em memória sobre o texto decifrado (vale em todas as páginas; recusado acima de 5.000 veículos no condomínio).
- **Gerar chave:** `openssl rand -base64 32` → `FIELD_ENCRYPTION_KEYS=k1:<resultado>`; `BLIND_INDEX_KEY=$(openssl rand -hex 32)`.
- **Guarde as chaves fora do backup do banco** (gerenciador de segredos). Sem elas os dados pessoais são irrecuperáveis. Trocar `BLIND_INDEX_KEY` é possível
  (ver "Trocar a `BLIND_INDEX_KEY`" abaixo).
- **Instalação que já tinha dados em texto puro:** os valores antigos continuam legíveis; suba uma vez com `FIELD_ENCRYPTION_MIGRATE=true`, confira o log
  ("PII encryption migration finished") e desligue a variável.
- **Trocar a `BLIND_INDEX_KEY`:** o índice é recalculado a partir do texto decifrado, então nada se perde. **Pare todas as instâncias**, troque a variável e suba:
  na partida o `BlindIndexReindexer` confere uma linha de amostra de cada tabela e, se o hash não bate com a chave atual, recalcula todos
  (log "Blind indexes rebuilt for N row(s)"). Linhas com hash nulo (dados anteriores à coluna `email_hash`) também são preenchidas. Duplicados legados
  ficam sem índice e geram aviso no log, em vez de impedir a partida. Desligável com `portaria.crypto.reindex-blind-on-start=false`.
- **Rotação:** `FIELD_ENCRYPTION_KEYS=k1:<antiga>,k2:<nova>` + `FIELD_ENCRYPTION_ACTIVE_KEY_ID=k2` + uma partida com `FIELD_ENCRYPTION_MIGRATE=true`; só depois remova a `k1`.
- **Fora do escopo desta camada:** o banco em si (disco/backup) deve ter criptografia de armazenamento própria; logs e auditoria não recebem dados pessoais.
