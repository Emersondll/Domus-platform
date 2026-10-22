# Agente local de contingência (Linux)

Executável Java separado (`agent/`) que roda no condomínio e mantém a portaria funcionando de forma **degradada**
e controlada quando a internet ou a central caem. Não substitui controladoras com regras locais nem sistemas de
segurança certificados.

## Como funciona
- **Identidade:** cada instalação tem `agentId` e uma chave própria (hash SHA-256 na central), rotacionável
  (`java -jar agent.jar --rotate-key`; a chave antiga vale por 24 h, até a nova ser usada) e revogável pela central.
- **Política:** a central entrega uma política **assinada** (HMAC-SHA256 sobre os bytes recebidos), **versionada** e com
  **validade** (padrão 6 h): entradas permitidas e credenciais aceitáveis offline (ID, assinatura esperada, janela, quota).
  O segredo de assinatura dos QR Codes nunca sai da central. O agente rejeita política adulterada, destinada a outro agente,
  mais antiga que a atual ou vencida.
- **Contingência só offline:** com a central acessível, o agente responde `DEFER_TO_CENTRAL`. Sem ela, valida o QR Code
  contra a política: token, entrada, janela de validade, quota (uso único respeitado) e validade da própria política.
- **Fila persistente (SQLite):** todo uso/negação vira evento com ID único, sincronizado em lotes. A central deduplica
  pelo ID (entrega "pelo menos uma vez" + idempotência). Só se apaga localmente o que a central confirmou.
- **Falha fechada:** se não for possível gravar o uso (disco cheio/arquivo ilegível), a porta **não abre**. Se a
  controladora não confirmar, o uso reservado é devolvido e a negação é registrada.
- **Queda no meio da operação:** reservas não finalizadas viram `OFFLINE_ENTRY_UNCERTAIN` ao reiniciar; o uso continua
  consumido e a central abre um conflito para revisão humana.
- **Comandos da central (pull):** o agente busca comandos de abertura; cada um é executado no máximo uma vez (marcado antes
  de agir), nunca depois de vencer (TTL 60 s) e o resultado é reportado: `DOOR_OPENED` só se a controladora confirmou,
  senão `COMMAND_FAILED`.
- **Reconciliação:** ao voltar, a central aplica os usos sem ultrapassar a quota e registra **conflitos**: revogada
  antes do uso (revogação depois da queda não chega ao agente), vencida, quota estourada, credencial desconhecida, entrada
  incerta. A gestão do condomínio consulta e resolve em `/sync-conflicts`.
- **API local (127.0.0.1):** `POST /validate` e `GET /status`, protegidas por `X-Local-Key`.

## Instalação (Linux)
1. `mvn -f agent/pom.xml package` gera `agent/target/agent-*.jar`. Copie para `/var/lib/portaria-agent/bin/agent.jar` (é o jar que o launcher executa e que a
   atualização automática substitui) e instale `agent/install/portaria-agent-run.sh` em `/opt/portaria-agent/` (dono root, `chmod 755`).
2. Crie o usuário `portaria-agent` e `/var/lib/portaria-agent` (com `bin/`, gravável por ele).
3. Na central, cadastre o agente (`POST /api/v1/condominiums/{id}/agents`); guarde `apiKey` e `policySecret` (exibidos uma vez).
4. Crie `/etc/portaria-agent/agent.env` a partir de `agent/install/agent.env.example` (`chmod 600`).
5. `sudo cp agent/install/portaria-agent.service /etc/systemd/system/ && sudo systemctl enable --now portaria-agent`.
6. Verifique: `curl -H "X-Local-Key: ..." localhost:8099/status`.
Use **HTTPS** na `PORTARIA_CENTRAL_URL` (proxy reverso com TLS na central). Defina também `PORTARIA_STORE_KEY` (cifra o armazenamento local).

## Atualização segura (opcional)
Desligada por padrão. Para ligar, defina `PORTARIA_UPDATE_URL` (pasta HTTPS com `manifest.json` e os jars) e `PORTARIA_UPDATE_PUBLIC_KEY`.
- **Quem pode publicar:** só quem tem a chave privada de lançamento (Ed25519). A central e o servidor de arquivos **não** são confiáveis para isso: um servidor
  comprometido não consegue fazer o agente instalar nada, porque o manifesto precisa estar assinado e o jar precisa bater com o hash e o tamanho assinados.
- **Regras do agente:** assinatura primeiro; depois canal, janela de liberação (`notBefore`), tamanho máximo (200 MB), **nunca versão igual ou menor** (sem retrocesso nem
  repetição de manifesto antigo), hash SHA-256 do arquivo. HTTP puro só para `localhost` (testes). Só instala com nenhuma entrada sendo registrada.
- **Instalação e reversão:** grava `agent.jar.new` → guarda o atual como `agent.jar.previous` → troca atômica → marcador `update-pending` → sai com código 75 → o systemd
  reinicia pelo launcher. Se a versão nova encerrar em menos de 60 s por 3 vezes seguidas, o launcher restaura o `.previous`; se ficar de pé, confirma.
- **Publicar uma versão** (na máquina de quem lança, chave privada offline): defina a versão (`mvn versions:set -DnewVersion=1.2.0`; versões `SNAPSHOT` nunca se atualizam),
  `mvn -f agent/pom.xml package`, depois
  `java -cp agent/target/agent-1.2.0.jar br.com.domus.agent.update.SignTool genkey ./chaves` (uma vez; a pública vai para os agentes) e
  `java -cp agent/target/agent-1.2.0.jar br.com.domus.agent.update.SignTool sign agent/target/agent-1.2.0.jar 1.2.0 stable ./chaves/release-private.key ./release [notBefore]`;
  publique o conteúdo de `./release` na `PORTARIA_UPDATE_URL`.
- **Provas:** `UpdateTest` (14 cenários), `scripts/test-agent-launcher.sh` e `scripts/e2e-agent-update.sh` (jars reais: 1.0.0 → 1.1.0 → reinício → confirmação; manifesto adulterado e de outra chave recusados).

## Validado (teste automatizado: 36 no agente, 12 do lado central, mais `scripts/e2e-contingency.sh`)
1 queda de internet; 2 API central indisponível (503); 3 reinicialização; 4 perda da controladora; 5 eventos/comandos
duplicados; 6 credencial e política vencidas; 7 revogação antes e depois da queda; 8 retorno da conectividade;
9 DNS indisponível; 10 arquivo corrompido e armazenamento esgotado. O script sobe central e agente reais, derruba a
central com `kill -9`, valida offline e confere a reconciliação.

## Limitações conhecidas (honestas)
- **Atualização:** não há rotação da chave de lançamento (trocá-la exige reinstalar a chave pública nos agentes); não há instalador/pacote (.deb); só Linux/systemd; o jar
  não tem assinatura de código da JVM (a confiança é a do manifesto); a reversão depende do launcher (se o jar novo nem inicia, o launcher cobre; se o launcher for
  alterado por alguém com root, nada protege); a custódia da chave privada de lançamento é responsabilidade de quem publica.
- **Controladora simulada:** `SimulatedDoor`. Falta o adaptador da controladora real (depende do fabricante e do teste
  em campo, inclusive durante perda de conectividade).
- **Revogação durante a queda** não chega ao agente: um convite revogado pode ser usado offline até a política vencer.
  A janela é limitada pela validade da política e vira conflito auditável. Reduza o TTL se o risco for inaceitável.
- **Quota offline** é por agente: dois agentes/entradas offline ao mesmo tempo podem estourar uma credencial multiuso
  (detectado como `QUOTA_EXCEEDED` na volta).
- **Dados em repouso:** política (contém as assinaturas dos QR Codes ativos), fila de eventos e chave de API ficam cifradas (AES-256-GCM, chave derivada de
  `PORTARIA_STORE_KEY`, ≥ 32 caracteres, obrigatória). O contexto da linha entra como dado autenticado: mover um valor cifrado para outra linha é detectado.
  **Chave diferente da usada antes = o agente se recusa a iniciar e não toca no arquivo** (recriar perderia usos offline não sincronizados); guarde uma cópia
  da chave fora da máquina. Armazenamento de versão anterior (texto puro) é cifrado na primeira abertura. Mantenha o arquivo com permissão 600 e o disco cifrado;
  a chave fica no `agent.env` (600), então quem tem root na máquina continua capaz de ler tudo.
- **Corrupção** do arquivo perde eventos ainda não sincronizados (o arquivo ruim é preservado como `.corrupt-<ts>`).
- **Transporte:** autenticação por chave sobre TLS; mTLS com certificado por dispositivo é evolução futura.
- **Atualização segura** (binário assinado + verificação) **não implementada**: hoje a atualização é manual; publique
  releases com SHA-256 e assinatura e valide antes de trocar o jar.
- Relógio: a validade depende do relógio local; mantenha NTP ativo.
- Leitor online automático (QR na entrada com a central acessível) exige uma validação por agente na central: futuro.
