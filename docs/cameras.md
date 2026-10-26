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
   (`sourceOnDemand`: o RTSP só é aberto enquanto há espectador) e devolve uma URL HLS com um **token HMAC de 10 minutos preso
   àquele caminho**. O endereço RTSP e a senha da câmera **nunca chegam ao navegador**.
4. O painel renova o token antes de vencer. Cada requisição de lista e de trecho passa pelo MediaMTX, que pergunta ao backend
   (`/internal/media/auth`) se o token vale para aquele caminho.
5. Cada abertura de visualização grava `camera.view` na auditoria, na mesma transação. Morador e quem é de outro condomínio recebem 404.

**Monitoramento na mesa e no atendimento:** ao abrir um condomínio, e também ao atender uma chamada dele, o operador vê o bloco
"Câmeras do condomínio" com as câmeras ativas e o estado de cada uma. O vídeo **só abre quando o operador pede** ("Monitorar
<câmera>" ou "Abrir até 4 câmeras"), nunca sozinho, porque cada abertura é auditada e usa banda. Máximo de 4 ao mesmo tempo.
Cadastro e edição continuam na aba Câmeras.

Abrir o vídeo **não aciona nenhuma porta**. Se o servidor de mídia estiver desligado ou fora do ar, o painel mostra o erro; nunca
mostra imagem simulada.

## Subir o servidor de mídia (desenvolvimento)

```bash
docker run -d --name domus-media --network host \
  -v "$PWD/deploy/media/mediamtx.yml:/mediamtx.yml:ro" bluenviron/mediamtx:1.15.1
```

O perfil `dev` já liga o recurso (`portaria.media.enabled`) com segredos públicos de desenvolvimento. Fora do `dev`:

| Variável | Para quê |
|---|---|
| `MEDIA_ENABLED=true` | liga o recurso (padrão: desligado) |
| `MEDIA_TOKEN_SECRET` | ≥ 32 caracteres; assina os tokens de visualização |
| `MEDIA_PUBLISH_SECRET` | senha de quem **publica** no servidor de mídia (fonte de teste, futuro agente local) |
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

## Limites (não escondidos)

- **Nenhuma câmera real foi testada.** Compatibilidade de codec (o HLS exige H.264; H.265 não toca na maioria dos navegadores) e
  caminhos RTSP variam por fabricante.
- **Câmera atrás de NAT do condomínio:** a central na nuvem não alcança o RTSP da rede local. O modelo "puxar" só serve quando
  o RTSP é alcançável (rede privada/VPN). Para o caso comum, o **agente local deveria publicar** o fluxo no MediaMTX (a autorização
  de publicação já existe: `action=publish` com `MEDIA_PUBLISH_SECRET`), mas o agente **ainda não faz isso**.
- **Sem gravação e sem histórico de vídeo.** Há **um quadro** por aviso de visita (câmera de entrada), cifrado e com retenção, desligado por padrão (`docs/plano-cameras-visitas.md`). Retenção e acesso a gravações são decisão LGPD (`docs/lgpd.md`).
  O vídeo é só ao vivo.
- **Latência HLS** de alguns segundos; para baixa latência seria preciso WebRTC (não implementado).
- **Quem vê o quê:** a portaria vê **todas** as câmeras ativas. O morador vê ao vivo **só as câmeras de entrada que o síndico
  liberou** (`resident_viewable`, no máximo 4 por condomínio; padrão desligado), em Mais > Câmeras no app. Cada abertura do
  morador é auditada (`camera.view.resident`); câmera não liberada, equipe e outro condomínio recebem 404. Desativar a câmera
  retira a liberação. Sem gravação. A decisão jurídica de mostrar imagem de áreas comuns aos moradores está em `pendencias.md` (D21).
- **Produção:** `/internal/*` não pode ser publicado no proxy reverso (o `Caddyfile` bloqueia `/internal` com 404; confira se mudar o proxy); o MediaMTX precisa de TLS
  na frente e `hlsAllowOrigin` restrito à origem do painel (hoje `*` no arquivo de exemplo).
- A conversão RTSP → HLS roda no MediaMTX (MIT), executável **separado**; ele não é embutido no jar do Domus.
