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
1. `mvn -f agent/pom.xml package` gera `agent/target/agent-*.jar`. Copie para `/opt/portaria-agent/agent.jar`.
2. Crie o usuário `portaria-agent` e `/var/lib/portaria-agent`.
3. Na central, cadastre o agente (`POST /api/v1/condominiums/{id}/agents`); guarde `apiKey` e `policySecret` (exibidos uma vez).
4. Crie `/etc/portaria-agent/agent.env` a partir de `agent/install/agent.env.example` (`chmod 600`).
5. `sudo cp agent/install/portaria-agent.service /etc/systemd/system/ && sudo systemctl enable --now portaria-agent`.
6. Verifique: `curl -H "X-Local-Key: ..." localhost:8099/status`.
Use **HTTPS** na `PORTARIA_CENTRAL_URL` (proxy reverso com TLS na central).

## Validado (teste automatizado: 16 no agente, 12 do lado central, mais `scripts/e2e-contingency.sh`)
1 queda de internet; 2 API central indisponível (503); 3 reinicialização; 4 perda da controladora; 5 eventos/comandos
duplicados; 6 credencial e política vencidas; 7 revogação antes e depois da queda; 8 retorno da conectividade;
9 DNS indisponível; 10 arquivo corrompido e armazenamento esgotado. O script sobe central e agente reais, derruba a
central com `kill -9`, valida offline e confere a reconciliação.

## Limitações conhecidas (honestas)
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
