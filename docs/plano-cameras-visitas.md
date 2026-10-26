# Plano: telas de câmeras e imagem da entrada junto do pedido de visita

Status: **implementado em 2026-10-26 com câmera simulada** (fases 1 a 6; ver a seção 7). Redigido em 2026-10-26. Base: `docs/cameras.md` (vídeo ao vivo já existe), fluxo de visita
no balcão (`visit_request`, V9/V14) e `docs/lgpd.md`.

## 1. Objetivo
1. **Telas de câmeras completas** para a portaria: várias câmeras ao mesmo tempo, tela cheia, edição e desativação.
2. **Câmera de entrada de visitas.** Uma câmera por entrada é marcada com esse papel. Quando a portaria avisa o morador da chegada
   de uma visita ou prestador, a **imagem da câmera vai junto**, e o morador a vê no app antes de autorizar ou negar.

Não é objetivo: reconhecimento facial, gravação contínua, armazenamento de vídeo, abrir porta pela imagem. A autorização do morador
continua **não abrindo a porta** (regra existente: quem libera é a portaria).

## 2. Como o fluxo fica (em dois passos, de propósito)
```
Portaria                          Backend                          Morador (app)
1. "Capturar imagem da entrada" → captura 1 quadro da câmera de
                                  entrada, guarda cifrado e
                                  temporário (10 min), devolve id
   ← prévia da imagem
2. confere se a imagem mostra a   (se não serve, captura de novo)
   pessoa certa; preenche nome,
   unidade, tipo e envia o pedido
   com o id da imagem            → vincula a imagem ao pedido,
                                  notifica o morador ("com foto") → vê o pedido com a imagem,
                                                                    autoriza ou nega
```
Por que dois passos: a portaria **vê o que vai enviar** (evita mandar a foto errada ou de outra pessoa), a falha da câmera não trava o
aviso (o pedido segue **sem imagem**, e o morador vê "sem imagem") e o pedido continua funcionando sem câmera nenhuma.

## 3. Decisões que dependem de você (preciso delas antes da fase 3)
| # | Decisão | Minha recomendação |
|---|---|---|
| D12 | **Retenção da imagem do visitante** | apagar **30 dias** após o pedido (o nome do visitante já fica no histórico; a imagem é mais sensível). Imagem não usada em pedido: 10 min |
| D13 | **Base legal e aviso** (LGPD): quem decide, aviso de videomonitoramento na entrada, quem é o controlador | jurídico define; o sistema só guarda o mínimo e registra cada visualização |
| D14 | **O morador pode ver a imagem do histórico** ou só enquanto o pedido está aberto? | só dos pedidos da própria unidade, durante a retenção (D12) |
| D15 | **Onde roda a captura de quadro** (ver 4.2) | `ffmpeg` no mesmo servidor do MediaMTX, por um serviço pequeno; a imagem de câmera real exige isso |

Sem D12/D13 respondidas, eu **não ligo** a captura por padrão (recurso desligado, como a abertura remota).

## 4. Fases

### Fase 1 — Telas de câmeras (sem imagem de visita; independente das outras)
- **Grade de câmeras** na aba Câmeras: até 4 vídeos ao vivo ao mesmo tempo (limite para não sobrecarregar o MediaMTX e o navegador),
  cada um com a sessão renovada automaticamente; **tela cheia**; indicador online/offline; erro explícito se uma câmera falhar sem
  derrubar as outras.
- **Editar câmera** (nome, entrada, endereço RTSP, variável de credencial) e **desativar/reativar** (hoje só cadastra). Novo
  `PUT devices/{id}` e `POST devices/{id}/active` (MANAGE), com auditoria e a mesma validação de host (anti-SSRF).
- **Papel "câmera de entrada de visitas":** coluna nova `device.visitor_entrance` (booleano), no máximo **uma por entrada** por
  condomínio (índice parcial único). Marcar/desmarcar em `POST devices/{id}/visitor-entrance` (MANAGE).
- Mock: continua o padrão de teste do ffmpeg publicado no MediaMTX (`fake-src`) e o adaptador `SIMULATOR`.

### Fase 2 — Captura do quadro (`SnapshotSource`)
Interface `SnapshotSource.capture(Device) -> byte[] (JPEG)`, com timeout de 3 s. Implementações, na ordem:
1. **`SIMULATOR`** (mock, é o que vale até haver câmera real): gera um JPEG sintético com o texto "IMAGEM SIMULADA" e a hora.
   Assim o fluxo inteiro é testável sem câmera, e a imagem **nunca** parece real.
2. **Via servidor de mídia:** `ffmpeg -frames:v 1` sobre o caminho da câmera no MediaMTX (reaproveita o RTSP e as credenciais que o
   backend já protege). Prova com a fonte de teste do ffmpeg.
3. **HTTP/ONVIF** (`GetSnapshotUri` ou URL de snapshot da câmera) via `HostPolicy`. Só depois de haver equipamento para validar.

Normalização no servidor: redimensiona para no máximo 640 px no lado maior, JPEG qualidade ~70, teto de 200 KB; recusa qualquer
coisa que não decodifique como imagem. Falha ou timeout = erro claro para a portaria; nada é guardado.

### Fase 3 — Armazenamento, API e retenção (migração V15)
- **Tabela `visit_snapshot`:** `id`, `condominium_id`, `device_id`, `captured_by_user_id`, `captured_at`, `expires_at`,
  `visit_request_id` (nulo até o envio; **FK composta** com `visit_request` e índice único: uma imagem por pedido) e `content` **cifrado**
  (AES-GCM com a chave de campo, mesma rotação de chaves; sem URL pública, sem arquivo solto em disco).
- **Endpoints:**
  - `POST condominiums/{c}/entrance-snapshots` (OPERATE): captura e devolve `{id, expiresAt}`.
  - `GET condominiums/{c}/entrance-snapshots/{id}/image` (OPERATE, quem capturou): prévia da portaria.
  - `POST visit-requests` aceita `snapshotId` opcional: vincula (precisa ser do mesmo condomínio, do mesmo operador, não vinculada e
    não vencida) e a notificação ao morador diz "com imagem".
  - `GET me/visit-requests/{id}/snapshot` (morador **da unidade do pedido**; senão 404): `image/jpeg`, `Cache-Control: no-store`.
  - `GET visit-requests/{id}/snapshot` (portaria, READ).
- **Auditoria:** cada captura (`snapshot.capture`) e cada visualização pelo morador ou pela portaria (`snapshot.view`), no padrão
  existente (sucesso na mesma transação). A auditoria não guarda a imagem.
- **Retenção:** estender o `RetentionService`: apaga imagens sem pedido após 10 min e as vinculadas após o prazo D12; o contador
  de apagadas entra nas métricas. `docs/lgpd.md` passa a descrever a imagem (hoje diz que o sistema não guarda imagens).
- **Titular dos dados:** o visitante fotografado não é morador; o procedimento de exclusão sob pedido do titular entra no plano LGPD
  (D13) e fica registrado como pendência se não houver decisão.

### Fase 4 — Painel da portaria
- Aba **Visitantes**, seção "Avisar chegada": quando existe câmera de entrada, aparece **"Capturar imagem da entrada"** com a prévia;
  "Capturar de novo"; o envio leva a imagem. Sem câmera de entrada ou com recurso desligado: o formulário atual, sem mudança.
- Lista de pedidos recentes mostra o selo "com imagem" e a miniatura (carregada com o token, como blob, porque `<img>` não envia o
  cabeçalho `Authorization`).
- Aba Câmeras: botão de papel "Câmera de entrada de visitas" e a grade da fase 1.

### Fase 5 — App do morador
- O cartão "está na portaria e aguarda a sua autorização" mostra **a imagem** (com o aviso "imagem da câmera da portaria, apagada em
  N dias") acima de Autorizar/Negar; sem imagem, mostra "sem imagem disponível".
- Aba Visitas: pedidos recentes com a imagem enquanto durar a retenção (D14).
- Notificação na aba Visitas diz "com imagem". Nada de download ou compartilhamento pelo app (limite honesto: captura de tela
  do celular não dá para impedir).

### Fase 6 — Provas e documentação
- **Backend (IT):** portaria captura e vincula; outro operador não vincula a imagem alheia; imagem vencida e já vinculada = 409;
  morador de **outra unidade** e de **outro condomínio** = 404; sem token = 401; auditoria de captura e de visualização; limite de
  tamanho e arquivo que não é imagem; câmera fora do ar = erro claro e pedido sem imagem funciona; expurgo de imagens.
- **Tela:** testes do painel e do app com `fetch` falso (prévia, envio com `snapshotId`, imagem no cartão do morador, sem imagem).
- **Navegador real:** E2E com a câmera de entrada simulada: capturar → enviar → o morador vê a imagem → autoriza → a portaria registra
  a entrada. Passo extra com a fonte do ffmpeg pelo caminho do MediaMTX.
- Esquema mudou: `restore-drill`. Como grava imagem cifrada, rodar também o `load-test` (peso das escritas).
- Atualizar `docs/cameras.md`, `docs/lgpd.md`, `docs/STATUS.md`, `pendencias.md`, `docs/product-scope.md` ("Histórico com fotos" hoje
  consta como **Externo**).

## 5. Ordem e entregas
| Passo | Entrega | Depende de |
|---|---|---|
| 1 | Fase 1: grade, tela cheia, editar/desativar, papel de entrada | nada |
| 2 | Fases 2 e 3 com o mock (`SIMULATOR`), recurso **desligado por padrão** | D12, D13 respondidas para ligar |
| 3 | Fases 4 e 5 (painel e app) | passo 2 |
| 4 | Captura via MediaMTX com a fonte de teste do ffmpeg | D15 |
| 5 | Câmera real (HTTP/ONVIF) | equipamento (você disse que será mocado agora) |

Cada passo termina com testes, `mvn verify`, documentos atualizados e commit; push só com a sua autorização.

## 6. Riscos e limites declarados
- **Dado pessoal sensível por natureza** (imagem de pessoa): o recurso fica desligado até D12/D13; não declaro conformidade com a LGPD.
- **Sem câmera real**, a captura "de verdade" só é provada com a fonte de teste; a compatibilidade com cada fabricante fica pendente.
- **Câmera atrás de NAT:** vale o mesmo limite do vídeo ao vivo (a central precisa alcançar o RTSP, ou o agente local publicar o fluxo,
  o que ainda não existe).
- A imagem prova **quem estava na entrada naquele momento**, não que a pessoa é quem disse ser; a tela não deve induzir a
  "reconhecimento".
- Imagem capturada pela portaria pode não corresponder ao visitante se a câmera pegar outra pessoa: por isso a prévia obrigatória.

## 7. O que foi entregue (2026-10-26) e o que mudou em relação ao plano
- **Fase 1:** grade de até 4 câmeras ao vivo, tela cheia, editar (`PUT devices/{id}`), desativar/reativar e marcar a câmera de entrada
  de visitas (`device.active`, `device.visitor_entrance`, índice único por entrada; migração **V15**). `CameraAdminIT` (3 testes).
- **Fases 2 e 3:** captura (`POST entrance-snapshots`) com prévia, vínculo ao pedido (`snapshotId`), imagem cifrada (campo AES-GCM em
  `visit_snapshot`, migração **V16**, não V15 como o plano previa), consulta pela portaria e pelo morador da unidade
  (404 para outras unidades e condomínios), `Cache-Control: no-store`, auditoria de captura e visualização, expurgo no `RetentionService`
  (10 min sem pedido; 30 dias com pedido). `VisitSnapshotIT` (4 testes).
- **Fontes de imagem:** `SIMULATOR` (imagem sintética listrada "IMAGEM SIMULADA") e **via MediaMTX + ffmpeg** (o backend lê o fluxo da câmera pelo
  servidor de mídia com um token de 60 s; a senha da câmera não aparece na linha de comando). Provado com a fonte RTSP de teste no E2E.
  **Não** foi feita a captura direta HTTP/ONVIF (`GetSnapshotUri`): falta equipamento.
- **Fases 4 e 5:** painel (capturar, prévia, "Capturar de novo", enviar, selo e imagem na lista) e app do morador (imagem no cartão de autorização
  e a seção "Avisos da portaria" na aba Visitas). Componente `AuthImage` compartilhado (a imagem é baixada com o token).
- **Fase 6:** E2E com 40 verificações, `restore-drill` PASS (16 migrações), teste de carga sem erros (0 em ~43 mil requisições) e captura
  simulada com p95 de 92 ms.
- **Recurso desligado por padrão** (`SNAPSHOTS_ENABLED=false`); o perfil `dev` e os testes ligam. D12 (30 dias) foi adotada como padrão
  configurável (`SNAPSHOTS_RETENTION_DAYS`); D13 (base legal, aviso de videomonitoramento) **continua jurídica e em aberto**; D15: o ffmpeg precisa
  estar no servidor do backend (`FFMPEG_PATH`) para câmeras reais.
