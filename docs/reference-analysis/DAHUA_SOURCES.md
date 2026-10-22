# Conferência das fontes do adaptador `DAHUA_CGI` (2026-10-21)

**Conclusão:** a documentação oficial **não pôde ser obtida**. A API HTTP da Dahua é entregue por revendedor mediante NDA (relato em fórum:
a versão mais recente conhecida é a 2.11 de 2017) e a da Intelbras é enviada sob solicitação ao suporte (portal exige chatbot). Tudo abaixo vem de
fontes públicas **não oficiais**. O adaptador continua **não validado**, e a primeira instalação precisa de teste em campo.

| Afirmação usada no código | Fonte pública | Situação |
|---|---|---|
| Autenticação HTTP Digest (MD5, `qop=auth`) | Relatos em [ipcamtalk](https://ipcamtalk.com/threads/dahua-http-api-ivs-subscribe-to-events.80339/) usando `curl --digest` contra `eventManager.cgi` | Corroborada (equipamentos Dahua; não Intelbras específico) |
| `GET /cgi-bin/accessControl.cgi?action=openDoor&channel=1&UserID=101&Type=Remote` | Posts em [fóruns de Home Assistant](https://community.home-assistant.io/t/dahua-vto-to-mqtt-broker/169781?page=18) e ipcamtalk, para **interfones VTO** | Corroborada só para VTO; **não confirmada** para controladoras ASI/Intelbras |
| Resposta `OK` no corpo = comando aceito | Mesmos relatos | Corroborada informalmente; texto exato pode variar por firmware |
| `GET /cgi-bin/eventManager.cgi?action=attach&codes=[...]` é um fluxo `multipart/x-mixed-replace` que nunca termina | ipcamtalk e o código do módulo [node-dahua-api](https://github.com/nayrnet/node-dahua-api) (lê com `Accept: multipart/x-mixed-replace`) | Corroborada |
| Linhas `Code=<código>;action=<Start/Stop/Pulse>;index=<n>[;data={json}]` | Código do `node-dahua-api` (separa por `;` e lê `Code=`, `action=`, `index=`, `data={`) | Corroborada |
| Parâmetro `heartbeat=N` para keep-alive | Relato de fórum de que parâmetros extras definem keep-alive; **o nome literal não foi visto em fonte** | **Não confirmada**: se o equipamento ignorar, o detector de silêncio (15 s) derruba e reconecta a cada 15 s |
| Evento `AccessControl` com `UserID` e `Status` (1 = concedido) | Nenhuma fonte encontrada | **Hipótese**; o código só confirma comando se o `UserID` devolvido for `portaria-<commandId>` |
| Evento `DoorStatus` com `Status` `Open`/`Close` | Nenhuma fonte encontrada | **Hipótese**; só informativo, nunca confirma comando |

## Proveniência e implementação independente (clean-room)
- O formato do fluxo de eventos (`Code=...;action=...;index=...;data={...}`) é parte do protocolo do equipamento, que é fato técnico, e não expressão
  criativa de quem o documentou. Ele foi **lido** em material público apenas para entender o protocolo.
- O módulo `node-dahua-api` está sob **GPL-3.0**. **Nenhum código dele foi copiado, traduzido ou adaptado**: a implementação deste repositório
  (`DahuaEventStream`, `DahuaEventMapper`, `DigestAuth`) é em outra linguagem, com estrutura própria, e foi verificada por varredura de trechos
  (0 trechos coincidentes, 2026-10-22). Regras daqui em diante: **não adicionar esse módulo como dependência, não portar seu código e não
  colar trechos de fóruns**; ao ajustar o mapeador, usar apenas o que for observado no equipamento (fixtures sem dados pessoais).
- Se um dia a Dahua/Intelbras fornecer a documentação oficial sob NDA, **verificar se o NDA permite publicar o que dela se derive**
  antes de citar trechos em `docs/`.

## O que isso muda
- Relato de usuário de uma controladora **ASI6213S-PW**: `eventManager.cgi` **não entregou** os eventos de reconhecimento facial e ele recorreu a
  `snapManager.cgi?action=attachFileProc`. Em alguns modelos a assinatura pode não funcionar como aqui; o plano B é o equipamento enviar eventos
  por HTTP para a central (endpoint de eventos de dispositivo já existente) ou usar o agente local.
- O desenho é **seguro por omissão**: se o firmware não devolver o nosso identificador no evento, **nenhum comando é confirmado** e termina `UNKNOWN`.
  O pior caso de uma hipótese errada é a porta abrir sem a central saber, nunca o contrário.
- Antes de produção, pedir ao fabricante/revendedor a versão do documento para o **modelo exato** e rodar o roteiro de campo abaixo.

## Roteiro de campo (com a porta supervisionada)
1. `curl --digest -u usuario:senha "http://IP/cgi-bin/magicBox.cgi?action=getSystemInfo"` responde 200?
2. `curl --digest -N -u usuario:senha "http://IP/cgi-bin/eventManager.cgi?action=attach&codes=[All]&heartbeat=5"` e abrir a porta por cartão: quais `Code=` aparecem e com que `data`?
3. `curl --digest "http://IP/cgi-bin/accessControl.cgi?action=openDoor&channel=1&UserID=portaria-teste&Type=Remote"`: a porta abre? O evento seguinte devolve `portaria-teste`? Em qual campo?
4. Ajustar `DahuaEventMapper` aos nomes reais e registrar os eventos reais (sem dados pessoais) como fixture dos testes.
