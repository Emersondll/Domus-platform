# Câmeras e vídeo ao vivo

Estado: **implementado e provado com uma fonte RTSP de teste** (vídeo ao vivo, grade, edição e a imagem da câmera de entrada, ver `docs/plano-cameras-visitas.md`) (padrão de imagem do ffmpeg). **Não foi testado com uma câmera
real** nem com ONVIF `GetStreamUri`; isso depende de equipamento e está em `pendencias.md`.

## Como funciona

```
câmera (RTSP) ──► MediaMTX (RTSP → HLS) ──► navegador do painel (hls.js)
                     ▲  valida cada requisição
                     └── POST /internal/media/auth ──► backend Domus
```

1. O síndico (ou a administração) cadastra a câmera na aba **Câmeras** com o endereço RTSP, **sem usuário e senha** na URL
   (`config.rtspUrl`). Se a câmera exige login, informa só o **nome de uma variável de ambiente** (`config.rtspCredentialsEnv`)
   que guarda `usuario:senha`. O banco nunca guarda a senha da câmera.
2. O host do RTSP precisa estar em `DEVICE_HTTP_ALLOWED_HOSTS` (a mesma lista que protege contra SSRF os outros adaptadores).
   Esquema diferente de `rtsp`/`rtsps`, credenciais embutidas ou host fora da lista = 400 no cadastro.
3. O operador (nível OPERATE ou mais) clica em **Ver ao vivo**. O backend registra o caminho `cam-<id>` no MediaMTX
   (`sourceOnDemand`: o RTSP só é aberto enquanto há espectador) e devolve uma URL HLS com um **token HMAC de 3 minutos preso
   àquele caminho**. O endereço RTSP e a senha da câmera **nunca chegam ao navegador**.
4. O painel renova o token antes de vencer. Cada requisição de lista e de trecho passa pelo MediaMTX, que pergunta ao backend
   (`/internal/media/auth`) se o token vale para aquele caminho.
5. Cada abertura de visualização grava `camera.view` na auditoria, na mesma transação. Morador e quem é de outro condomínio recebem 404.

**Monitoramento na mesa e no atendimento:** ao abrir um condomínio, e também ao atender uma chamada dele, o operador vê o bloco
"Câmeras do condomínio" com as câmeras ativas e o estado de cada uma. O vídeo **só abre quando o operador pede** ("Monitorar
<câmera>" ou "Abrir até 4 câmeras"), nunca sozinho, porque cada abertura é auditada e usa banda. Máximo de 4 ao mesmo tempo.
Cadastro e edição continuam na aba Câmeras.

O player tem tempo limite de 20 s para conectar e, se a câmera ou o servidor de mídia falharem, tenta de novo com espera crescente (5, 10, 20, 30, 30 s) antes de pedir para fechar e abrir; cada tentativa abre uma sessão e é auditada. Erro de permissão (404/403) não é repetido. A mensagem diz o que provavelmente falhou: **sem resposta** = servidor de mídia fora do ar; **404** = o servidor está no ar, mas a câmera não envia vídeo; **401/403** = acesso recusado.

Abrir o vídeo **não aciona nenhuma porta**. Se o servidor de mídia estiver desligado ou fora do ar, o painel mostra o erro; nunca
mostra imagem simulada.

## Subir o servidor de mídia (desenvolvimento)

Atalho com cinco fontes RTSP falsas (padrões de teste do ffmpeg, lidas sem token **só em desenvolvimento**): `scripts/dev-media.sh up` (`down`, `status`, `repair`). Cada fonte roda num laço que reabre o ffmpeg se ele cair (o servidor de mídia reiniciando, por exemplo); `repair` religa só as que morreram. A config é gerada a partir de `deploy/media/mediamtx.yml`; precisa de Docker e ffmpeg. Manualmente:

```bash
docker run -d --name domus-media --network host \
  -v "$PWD/deploy/media/mediamtx.yml:/mediamtx.yml:ro" bluenviron/mediamtx:1.15.1
```

O perfil `dev` já liga o recurso (`portaria.media.enabled`) com segredos públicos de desenvolvimento. Fora do `dev`:

| Variável | Para quê |
|---|---|
| `MEDIA_ENABLED=true` | liga o recurso (padrão: desligado) |
| `MEDIA_TOKEN_SECRET` | ≥ 32 caracteres; assina os tokens de visualização |
| `MEDIA_PUBLISH_SECRET` | senha única de quem **publica** no servidor de mídia: só para **fonte de teste**. O agente local não usa: publica como `agent-<id>` com a chave dele. Vazio = publicação por segredo único desligada (recomendado em produção) |
| `MEDIA_PUBLIC_HLS_URL` | URL pública do HLS que o navegador acessa (ex.: `https://video.exemplo.com.br`) |
| `MEDIA_API_URL` | API de controle do MediaMTX (padrão `http://127.0.0.1:9997`, só loopback) |
| `MEDIA_AUTH_ALLOWED_IPS` | IPs de onde o MediaMTX chama a validação (padrão loopback) |

## Provas executadas (2026-10-25)

- `CameraStreamIT` (4 testes, backend real + PostgreSQL): operador recebe sessão; o token só vale para o caminho da própria câmera
  (outro caminho, token adulterado, vazio ou vencido = 401; ação `api` = 401); publicar exige o segredo; morador, outro condomínio e
  sem token são recusados (404/404/401); câmera sem RTSP = 409; URL com credenciais, IP não permitido ou esquema `http` = 400.
- Manual com MediaMTX 1.15.1 real e ffmpeg publicando um padrão de teste: sessão emitida → playlist HLS 200 (H.264 640×360) →
  trecho `fMP4` 200 com token; sem token 401; token adulterado 401.
- `scripts/e2e-browser.py` com `E2E_LIVE_VIDEO=1` (Chromium real): o player do painel chega ao estado **"Ao vivo"** usando o HLS do
  MediaMTX. Sem o servidor de mídia o mesmo passo exige o erro explícito. Testes de UI cobrem as duas situações (36 testes).

## Sessões de vídeo revogáveis (B12, 2026-10-28)

Cada abertura de vídeo cria uma **sessão no banco** (`camera_view_session`, V22) e o token passa a ser `sessão.expiração.assinatura`.
O servidor de mídia chama `/internal/media/auth` a cada playlist e trecho; o backend confere a assinatura **e** se a sessão
continua ativa para aquela câmera. Por isso o acesso acaba na hora (a próxima requisição do player falha) quando:
a câmera é **desativada**; a liberação ao morador é **retirada** (só as sessões de morador caem); o **vínculo** da pessoa é revogado;
ou ela passa do teto de **12 sessões ativas** (`MEDIA_MAX_SESSIONS_PER_USER`; a mais antiga cai). A abertura de sessões do mesmo
usuário é serializada por um lock consultivo do Postgres (o teste de carga achou um deadlock e uma ultrapassagem do teto sem ele;
há teste de concorrência que falha sem o lock). **Limite que continua:** o token não está preso à pessoa; quem receber a URL
assiste até a sessão ser revogada ou vencer (3 minutos, renovados pelo player). Amarrar ao IP/cliente quebraria a rede móvel.

## Credenciais da câmera pela tela (CR5, 2026-10-28)

Na edição da câmera o síndico ou a administração cadastra **usuário e senha**. Eles são cifrados (AES-GCM, a chave fica fora do banco)
em `device_credential` (V23), **nunca saem pela API** (só "configurada, trocada em…"), não vão a log nem à auditoria (só
`device.credentials.set/clear`) e chegam apenas ao servidor de mídia, como `usuario:senha@` na fonte RTSP. Tem preferência sobre a
variável de ambiente (`rtspCredentialsEnv`, que continua valendo). Trocar a senha é cadastrar de novo; remover apaga. A
configuração comum da câmera recusa chaves que pareçam segredo (`password`, `token`, `secret`…), exceto as que terminam em `Env`.
**Isto não é um cofre dedicado:** quem tiver o banco **e** a chave de criptografia lê as credenciais (decisão D3, KMS, continua em aberto).

## Câmera atrás de NAT: o agente publica o vídeo (2026-10-28)

Cadastre a câmera com a opção "Vídeo publicado pelo agente local" (`config.videoSource = AGENT`, sem `rtspUrl` na central). O agente
puxa o RTSP **da rede local** e **empurra** o fluxo para o servidor de mídia da central (`ffmpeg -c copy`, conexão de dentro para
fora, nenhuma porta aberta no condomínio). Variáveis do agente: `PORTARIA_VIDEO_CAMERAS` (`id-da-câmera=rtsp://…;…`),
`PORTARIA_VIDEO_PUBLISH_URL`, `PORTARIA_FFMPEG`. O agente se autentica no servidor de mídia com o usuário `agent-<id do agente>` e a chave dele (a mesma da sincronização, relida a cada início do ffmpeg, então a rotação de chave vale); a central só aceita publicar a câmera **do próprio condomínio** marcada como publicada por agente, e um agente revogado ou de condomínio suspenso é recusado. Não há mais segredo compartilhado entre agentes. Se o ffmpeg
cair, o agente o reabre com espera 5, 10, 20, 30 s. O estado mostrado na tela para essas câmeras é "o agente está enviando vídeo agora?" (ao clicar em *Atualizar estado*, a central pergunta ao servidor de mídia se há alguém publicando; servidor fora do ar = estado desconhecido). **Limites:** o endereço da câmera e a chave do agente aparecem nos argumentos do
processo ffmpeg (visíveis a quem lê os processos da máquina do agente; nunca vão a log); a chave do agente aparece nos argumentos do mesmo processo; o vídeo só vai cifrado até a central se o servidor de mídia aceitar `rtsps`
(o `mediamtx.yml` de exemplo não liga TLS). Provado com `scripts/e2e-agent-video.py` (MediaMTX e ffmpeg reais, tudo em 127.0.0.1:
publica, HLS 200, sem token 401, o ffmpeg morto volta, agente parado = vídeo some). **Nunca testado com câmera real nem pela internet.**

## Limites (não escondidos)

- **Nenhuma câmera real foi testada.** Compatibilidade de codec (o HLS exige H.264; H.265 não toca na maioria dos navegadores) e
  caminhos RTSP variam por fabricante.
- **Câmera atrás de NAT do condomínio:** o modelo "puxar" só serve quando o RTSP é alcançável pela central (rede privada/VPN).
  Para o caso comum, o **agente local publica** o fluxo (seção acima), provado só com fonte de teste.
- **Sem gravação e sem histórico de vídeo.** Há **um quadro** por aviso de visita (câmera de entrada), cifrado e com retenção, desligado por padrão (`docs/plano-cameras-visitas.md`). Retenção e acesso a gravações são decisão LGPD (`docs/lgpd.md`).
  O vídeo é só ao vivo.
- **Latência HLS** de alguns segundos; para baixa latência seria preciso WebRTC (não implementado).
- **Quem vê o quê:** a portaria vê **todas** as câmeras ativas. O morador vê ao vivo **só as câmeras de entrada que o síndico
  liberou** (`resident_viewable`, no máximo 4 por condomínio; padrão desligado), em Mais > Câmeras no app. Cada abertura do
  morador é auditada (`camera.view.resident`); câmera não liberada, equipe e outro condomínio recebem 404. Desativar a câmera
  retira a liberação. Sem gravação.
  **Quem faz o quê no painel (aba Câmeras):** o operador vê o estado e o vídeo; cadastrar, editar, desativar, marcar a câmera de entrada
  de visitas e liberar ao morador só aparecem para síndico ou administração (a tela consulta `GET /devices/permissions`; o servidor
  continua sendo quem decide). O quadro "Câmeras para moradores: X liberadas" mostra o que o morador vê. Desde 2026-10-29 (decisão do titular) não há limite de câmeras liberadas, de horário (24 h) nem de unidade.
  Ao liberar uma câmera, os moradores recebem a notificação "Nova câmera disponível" (aba Avisos), e o app pede a ciência de que
  as imagens mostram outras pessoas antes de abrir o vídeo (ciência de tela, não consentimento jurídico). A decisão jurídica de mostrar imagem de áreas comuns aos moradores está em `pendencias.md` (D21).
- **Produção:** `/internal/*` não pode ser publicado no proxy reverso (o `Caddyfile` bloqueia `/internal` com 404; confira se mudar o proxy); o MediaMTX precisa de TLS
  na frente e `hlsAllowOrigin` restrito à origem do painel (hoje `*` no arquivo de exemplo).
- A conversão RTSP → HLS roda no MediaMTX (MIT), executável **separado**; ele não é embutido no jar do Domus.
