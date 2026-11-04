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
| `REQUIRE_VALID_CNPJ` (padrão `true` em produção) | não | exige CNPJ de 14 dígitos com verificadores corretos ao cadastrar empresa e condomínio (e guarda só os dígitos); em desenvolvimento e testes aceita identificadores fictícios, mas 14 dígitos sempre são conferidos **Em instalação existente, rode antes `python3 scripts/check-tax-ids.py`** (somente leitura: lista os CNPJs que seriam recusados). |
| `PROVISIONER_ENABLED` · `PROVISIONER_CLIENT_SECRET` (obrigatório no compose de produção) | não · sim | criação de contas da equipe pela tela (ver "Criar a conta da equipe pela tela") |
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
- **Backup do Keycloak é obrigatório, não opcional.** As contas de login da equipe e dos moradores (senha, MFA, o `sub` que as liga
  às associações) ficam no banco `keycloak`, no mesmo PostgreSQL. Restaurar só o `portaria` deixa todos sem conseguir entrar.
  Por isso use **`scripts/backup-all.sh`**, que salva `portaria` e `keycloak` (lista em `BACKUP_DBS`). A definição do realm está
  versionada em `deploy/keycloak/portaria-realm.json`, mas **não** contém usuários.

### Servidor único com Docker Compose (produção)
Nos comandos abaixo, `COMPOSE_FILES` aponta para a stack de produção e `PGSERVICE=db` (o serviço do banco tem outro nome que no dev):
```bash
export COMPOSE_FILES="--env-file deploy/.env.prod -f deploy/docker-compose.prod.yml" PGSERVICE=db
# agendar (cron), de preferência com BACKUP_GPG_RECIPIENT, e copiar o diretório para FORA do servidor:
scripts/backup-all.sh /var/backups/domus
```
**O que o backup NÃO inclui e você precisa guardar em outro lugar:** `deploy/.env.prod` (senhas, `FIELD_ENCRYPTION_KEYS`,
`BLIND_INDEX_KEY`, `CREDENTIAL_HMAC_SECRET`, `AGENT_POLICY_MASTER_SECRET`) — sem as chaves os dados pessoais cifrados são
irrecuperáveis, e sem o segredo do agente as políticas já emitidas deixam de validar. Os certificados do Caddy não precisam de cópia
(são emitidos de novo, respeitando o limite de emissões da autoridade). Gravações de vídeo e métricas do Grafana também ficam de fora.

**Recuperação num servidor novo (testada, ver abaixo):**
1. Instale Docker, traga o repositório e o `deploy/.env.prod` (com as **mesmas** chaves) e suba só o banco: `docker compose $COMPOSE_FILES up -d db`.
   O volume novo já nasce com `portaria` e `keycloak` vazios.
2. Restaure cada dump em um banco **novo** (o `restore.sh` confere o SHA-256 e nunca sobrescreve):
   `scripts/restore.sh <portaria-….dump> portaria_restored` e `scripts/restore.sh <keycloak-….dump> keycloak_restored`.
3. Troque os nomes (nada pode estar conectado; a API e o Keycloak ainda não foram iniciados). Os vazios ficam como `*_empty`, para desfazer:
   `ALTER DATABASE portaria RENAME TO portaria_empty; ALTER DATABASE portaria_restored RENAME TO portaria;` e o mesmo para `keycloak`
   (via `docker compose $COMPOSE_FILES exec -T db psql -U portaria -d postgres -c "…"`).
4. Suba o resto: `docker compose $COMPOSE_FILES up -d`. O Keycloak ignora a importação do realm porque ele já existe no banco.
5. Confira `/readiness`, entre com uma conta real e rode `scripts/restore-drill.sh` (com as mesmas variáveis) como sanidade.
- **Exercício feito em 2026-11-03** numa stack de produção real em portas locais (`docker-compose.prod.yml`): banco `portaria` com o esquema real (31 migrações, 636 200 linhas de auditoria, 67 MB) e um usuário no Keycloak. `backup-all.sh` **cifrado com GPG** (`BACKUP_GPG_RECIPIENT`, chave descartável) levou 6 s; os backups foram copiados por `rsync` para outro diretório (a "cópia de fora", **simulada**: não houve servidor remoto) e os `.sha256` conferidos lá; `down -v` apagou o volume e os backups locais; um banco novo recebeu a restauração **a partir da cópia de fora, decifrando com a chave GPG** (13 s para os dois bancos) e a API (`app`) subiu sobre ele: **Flyway aplicou a V32 e a V33 sobre o backup antigo, `/actuator/health/readiness` = UP em 15 s**, 33 migrações e 636 200 linhas de auditoria. O usuário do Keycloak voltou com o **mesmo id** (mesmo `sub`) e entra com a senha certa (a errada dá "Invalid user credentials").
  - **Prova do aviso sobre as chaves:** na primeira tentativa a API caiu em laço ao subir ("Encrypted value uses an unknown key id"), porque o backup tinha sido cifrado com chaves de campo diferentes das do `.env` do servidor novo. Com as **mesmas** chaves ela subiu. Ou seja: restaurar o banco sem o `deploy/.env.prod` original não recupera os dados pessoais, e a API recusa subir em vez de gravar por cima (falha segura).
  - **Defeito achado e corrigido:** com GPG, o `backup.sh` apagava o dump em claro mas deixava o `.sha256` dele sobrando (conferência na cópia de fora acusava "arquivo inexistente"); agora só o `.sha256` do `.gpg` fica. O `restore.sh` passou a aceitar `.dump.gpg` (confere o checksum do `.gpg`, decifra para um temporário e apaga).
  - **Limites:** máquina de desenvolvimento, não um servidor remoto; a cópia de fora foi um `rsync` local; o tempo (15 s) vale para 67 MB e não é um RTO; a API foi vista saudável, mas **não** foi testado um login pela API com token do Keycloak restaurado (o emissor do token depende do domínio público); `restore-drill.sh` não foi rodado contra a stack de produção.

## Saída de rede da API (contenção da stack de produção)
Na `deploy/docker-compose.prod.yml` só o **proxy** (Caddy) tem saída: ele precisa dela para obter o certificado. Banco, Keycloak e API ficam
na rede `internal`, que é `internal: true` (sem rota para a internet nem para a rede do servidor), e o proxy as alcança por dentro. Isso
limita o estrago de uma falha de SSRF ou de execução remota na API e impede que o Keycloak ou o banco "liguem para casa".
- **Medido em 2026-11-02 numa stack real (portas locais, domínio `*.localhost`):** antes, `app` e `keycloak` alcançavam o servidor
  (portas 80 e 443 do gateway) e `1.1.1.1:443`; depois, todos esses acessos ficam **bloqueados**, enquanto `app → db`, `proxy → app` e a
  validação de um token real do Keycloak pelo HTTPS do proxy (200 na descoberta, 401 sem token, **403 com token válido sem associação**,
  401 com token adulterado, 404 em `/actuator`) continuam funcionando, sem nenhum erro de rede nos logs. O proxy segue com saída.
- **Se a API precisa falar com controladoras HTTP/ONVIF/Dahua ou com o servidor de mídia** (`DEVICE_HTTP_ALLOWED_HOSTS`, `MEDIA_ENABLED`),
  some o override: `docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml -f deploy/docker-compose.prod.devices.yml up -d`.
  Ele dá à API uma rede **com saída irrestrita** (provado: com o override a API alcança `1.1.1.1:443`; sem ele, não). Por isso restrinja o
  destino no firewall do servidor. Exemplo **não testado** (ajuste a interface, a sub-rede da rede `devices` e os IPs): na cadeia
  `DOCKER-USER`, aceitar `-s <sub-rede devices> -d <IP da controladora> -p tcp --dport <porta>` e `-s <sub-rede devices> -d <IP do servidor
  de mídia>`, e descartar o restante vindo dessa sub-rede (`-j DROP`). Descubra a sub-rede com `docker network inspect portaria-prod_devices`.
- **Prometheus e Grafana** (profile `observability`) estavam numa rede interna e por isso **não publicavam** `127.0.0.1:9090` e
  `127.0.0.1:3000` (o Docker não publica porta de rede interna): em produção ficavam inacessíveis. Agora têm também a rede `host`, só
  para publicar a porta; **essa rede tem saída**, então o Grafana vem com telemetria e verificação de versão desligadas. Prometheus 200 e
  Grafana 200 no servidor depois da correção.
- **Guarda contra regressão:** `scripts/check-prod-network.py` (roda na CI, job `compose-config`) falha se banco, Keycloak ou API entrarem em
  rede com saída, se a rede interna deixar de ser interna ou se o override der saída a quem não deve. Provado: reintroduzir o defeito antigo
  (API na rede `edge`) faz o script falhar com exit 1.
- **Limites:** não filtra o tráfego do proxy nem o dos painéis de observabilidade; não substitui firewall de host nem mTLS; a stack
  foi medida localmente, não num servidor de produção.

## Endurecimento dos contêineres (2026-11-04)
No `docker-compose.prod.yml`: todos os serviços usam `no-new-privileges` e `cap_drop: [ALL]`. API, banco e proxy também rodam com sistema de arquivos **somente leitura** (gravável só `/tmp` em memória, o volume de dados do banco e os volumes do Caddy); o banco recebe de volta apenas `CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID` e `SETUID` (o entrypoint do Postgres precisa), e o proxy só `NET_BIND_SERVICE`. O **Keycloak não é somente leitura**: o comando `start` otimiza o Quarkus na primeira subida e grava em `/opt/keycloak`. Provado subindo a pilha inteira (banco, Keycloak, API e proxy) com essa configuração: saudável, painel 200, API sem token 401, realm do Keycloak 200. O Caddy registra um erro inofensivo ao tentar instalar a CA interna no sistema (só em domínio local).

## Associação de moradores e ticket de saída (V26, V27)

- **Código de adesão:** o síndico gera em Cadastros → Solicitações. Quem o tem só consegue **abrir um pedido**; o acesso nasce quando o síndico aprova. Trocar ou desligar o código vale na hora (pedidos já abertos continuam pendentes). Para o morador conseguir pedir, ele precisa ter conta no provedor de identidade: **decidido em 2026-11-02: o autocadastro do Keycloak fica ligado** (`registrationAllowed: true` em `deploy/keycloak/portaria-realm.json`, sem verificação de e-mail). A conta nova não tem papel nenhum; ela só passa a ver um condomínio depois que o síndico aprova o pedido com o código de adesão. **Em um Keycloak já existente o realm não é reimportado:** ligue "User registration" em Realm settings → Login. **Riscos aceitos:** não há CAPTCHA nem verificação de e-mail (qualquer pessoa pode criar contas sem papel; o bloqueio por tentativas do Keycloak vale para login, não para cadastro), e o e-mail informado não é confirmado. Se o abuso aparecer, as saídas são o "Registration reCAPTCHA" do Keycloak (chaves do Google) ou exigir verificação de e-mail (precisa de SMTP, decisão D5).
- **MFA da equipe (novo, 2026-11-02):** ao adicionar equipe ou administrador (síndico, operador, administrador da empresa ou da plataforma) é **obrigatório dar à pessoa o papel de realm correspondente no Keycloak** (`condo-manager`, `operator`, `company-admin`, `platform-admin`). É esse papel que faz o Keycloak exigir o OTP, e a API passou a recusar (403, "Staff role requires multi-factor authentication") quem tem a associação aqui mas não tem o papel lá. A exigência é a variável `REQUIRE_STAFF_MFA_ROLE` (padrão `true` em produção; `false` em desenvolvimento e testes, onde os tokens não vêm do Keycloak). **Ao atualizar uma instalação existente, confira antes que todo membro de equipe já tem o papel**, ou ele perde o acesso até recebê-lo. Quem é morador e equipe ao mesmo tempo perde só a parte de equipe.
- **Criar a conta da equipe pela tela (novo, 2026-11-03):** com `PROVISIONER_ENABLED=true` (padrão `true` em `docker-compose.prod.yml`), "Adicionar à equipe" e "Adicionar administrador" pedem só e-mail, nome e papel: a API cria a conta no Keycloak com a **conta de serviço `portaria-provisioner`** (permissão apenas de gerenciar usuários do realm), já com o papel de MFA e a troca de senha obrigatória, liga o `sub` sozinha e mostra uma **senha provisória uma única vez** (entregue por canal seguro). No primeiro acesso a pessoa configura o OTP e troca a senha (a ordem observada no Keycloak é OTP primeiro). Sem sobrenome no nome, o Keycloak também pede para completar o perfil. A criação do usuário no Keycloak não participa da transação do banco: se o vínculo falhar depois, a API apaga a conta recém-criada (se isso também falhar, o log pede para removê-la à mão). O fluxo manual (colar o `sub`) continua disponível pelo link "A conta já existe no Keycloak".
  - **Segredo:** `PROVISIONER_CLIENT_SECRET` (obrigatório em `deploy/.env.prod`, ≥ 32 caracteres aleatórios); o realm importado o recebe do mesmo valor. No Keycloak de desenvolvimento o padrão é `provisioner_dev_only`. **Em um Keycloak já existente o realm não é reimportado:** rode `KEYCLOAK_URL=... KEYCLOAK_ADMIN_PASSWORD=... PROVISIONER_CLIENT_SECRET=<valor do .env.prod> python3 scripts/setup-provisioner-client.py [realm]` (idempotente). Ele cria o client `portaria-provisioner` (confidencial, só *service accounts*, escopo completo desligado) e dá à conta de serviço os papéis `manage-users`, `query-users` e `view-users` de `realm-management`, com o mesmo escopo no client. Provado em 2026-11-03 num Keycloak 26.8 descartável importado sem o client: `scripts/verify_keycloak.py` (com `KEYCLOAK_URL`) passou inteiro, inclusive a criação de contas.
  - **Risco aceito:** quem comprometer a API passa a poder criar contas no Keycloak (e dar a elas qualquer papel de realm, inclusive `platform-admin`; o Keycloak não permite restringir `manage-users` a papéis específicos sem permissões refinadas). Sem uma associação no banco a conta não acessa nada, mas a API é quem grava as duas pontas. Se não quiser isso, `PROVISIONER_ENABLED=false` volta ao fluxo manual.
  - **Senha provisória perdida e saída da equipe (2026-11-04):** "Gerar nova senha" (equipe: Cadastros → Equipe; administradores: Administração) cria outra senha provisória, mostrada uma vez, e encerra as sessões da conta; o autenticador já configurado continua valendo. Só vale para contas **criadas pela plataforma** (tabela `provisioned_account`, V34) cuja pessoa não tenha acesso fora do escopo de quem pede; contas ligadas à mão no Keycloak ou compartilhadas com outra empresa recebem 409 e a senha só se troca no Keycloak. Ao revogar a última associação de uma conta criada pela plataforma, a conta é **apagada do Keycloak** (depois do commit; se falhar, o log pede a remoção manual) e o usuário local é anonimizado, o que libera o e-mail para um novo cadastro. Contas criadas à mão nunca são apagadas.
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
- Limitação de taxa em memória (por instância); com várias instâncias use armazenamento compartilhado (ex.: Redis). Regras (2026-11-04): **sem token** 600 req/min por IP; **com token Bearer** o IP só tem um teto de inundação de 6000/min (`RATE_LIMIT_*`/`portaria.rate-limit.bearer-per-ip-per-minute`) e o limite fino é **por usuário** (1200/min, `user-per-minute`, depois da autenticação), porque uma central ou um condomínio sai por um IP só; agente e dispositivo têm limite por identidade **mais** um teto por IP de 10× (o `X-Agent-Id` é informado pelo cliente e não pode ser usado para contornar o limite); as **falhas de autenticação** de máquina bloqueiam a dupla IP + identidade por 5 min (um equipamento com chave errada não derruba o agente do mesmo condomínio) e um teto de 10× por IP barra quem testa vários identificadores.
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
