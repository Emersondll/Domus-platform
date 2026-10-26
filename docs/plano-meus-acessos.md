# Plano: "Meus acessos" do morador, com a foto do cadastro (preparação para identificação facial)

Status: **plano, nada implementado.** Redigido em 2026-10-26. Base: `pendencias.md` (seção 4-A e perguntas ao jurídico em D16 a D18),
`docs/lgpd.md` e o que já existe de acesso e dispositivos.

## 1. Objetivo
1. O morador abre **"Meus acessos"** no app e vê **quando entrou e quando saiu** do condomínio, apenas os acessos **dele**.
2. O morador tem uma **foto de cadastro** (com consentimento registrado), mostrada nessa tela. A foto existe para, **no futuro**, a entrada
   ser por identificação facial.

Não é objetivo desta entrega: criar gabarito (modelo) facial, reconhecer rostos, decidir acesso por rosto, mostrar a rotina do morador à
portaria ou ao síndico sem regra. Nada disso é feito antes das decisões D16 a D18 e do parecer jurídico.

## 2. O que existe hoje e o que falta (verificado no código)
| Peça | Hoje | Falta |
|---|---|---|
| Histórico de acessos | `access_attempt`: só **visitantes** (convite validado e visitante autorizado na portaria) | **registro dos acessos do próprio morador** |
| Eventos de porta | `device_event`: tipo (`DOOR_OPENED`, `ACCESS_DENIED`...), dispositivo, instante. **Sem pessoa e sem sentido** | quem passou, por qual meio e se entrou ou saiu |
| Evento Dahua/Intelbras | o mapeador só aproveita o `UserID` quando é a **referência do nosso comando**; o resto é descartado | extrair a identidade de quem passou (cartão, usuário do terminal). Os campos do evento são **suposição não confirmada em campo** (B6) |
| Identidade de acesso do morador | não existe | vínculo morador ↔ cartão/QR/usuário do terminal |
| Foto do morador | não existe | armazenamento cifrado, consentimento, exclusão |
| Consentimento | não existe tabela | registro (quando, qual texto, revogação) |

## 3. Decisões que condicionam o plano
| # | Decisão | Efeito |
|---|---|---|
| D16 | Base legal e consentimento (foto e reconhecimento facial) | **bloqueia a coleta da foto em produção** (recurso desligado até responder) |
| D17 | Menores e dependentes; **quem vê o histórico** de acessos | primeira versão: **só o próprio titular**; dependentes ficam de fora; portaria e síndico não veem o histórico pessoal |
| D18 | Gabarito no terminal ou na central; fabricante e modelo | não afeta esta entrega; o desenho já assume "gabarito só no terminal" |
| D19 (nova) | **Prazo de retenção** do histórico de entradas e saídas | recomendo **90 dias**, configurável; confirmar com o jurídico |
| D20 (nova) | **Como saber o sentido** (entrada ou saída) em cada ponto de acesso | ver 4.1: sem leitor nos dois sentidos o sistema mostra "passagem" e não inventa o sentido |

O histórico de acessos (parte 1 do objetivo) **não depende de D16**: é dado do próprio titular, mostrado só a ele. A foto (parte 2) depende.

## 4. Fases

### Fase 1 — Registro dos acessos do morador (migração V17)
**4.1 Modelo**
- **`access_identity`** (identidade de acesso): `id`, `condominium_id`, `resident_id`, `kind` (`CARD`, `QR`, `TERMINAL_USER`, `APP`), `external_ref`
  (referência no equipamento, **cifrada** com índice cego para busca, como as placas), `active`, `created_at`, `revoked_at`. FK composta com `resident`.
  Uma referência ativa por condomínio (índice único). Cadastro por síndico/administração e, para QR e app, pelo próprio morador.
- **`resident_access_event`** (separado de `access_attempt` de propósito: outra finalidade, outra retenção, outro público):
  `id`, `condominium_id`, `resident_id`, `device_id`, `entrance`, `direction` (`IN`, `OUT`, `UNKNOWN`), `method` (`CARD`, `QR`, `TERMINAL_USER`,
  `APP`, `FACE` reservado), `result` (`GRANTED`, `DENIED`), `occurred_at`, `source_event_id`. Índice `(resident_id, occurred_at DESC)`.
- **Sentido por ponto de acesso:** configuração do dispositivo `direction: IN | OUT | BOTH_UNKNOWN`. Dois leitores (entrada e saída) dão o sentido certo.
  Com um leitor só, o evento fica **`UNKNOWN`** e a tela mostra **"passagem"**. O sistema **não adivinha** o sentido e **não mostra "está dentro"** sem sentido
  confiável (isso exigiria antipassback, fora do escopo).
- **Só gravamos eventos de pessoas cadastradas.** Referência desconhecida não gera registro pessoal (só um contador de métrica), para não acumular dado
  de quem não está no cadastro.

**4.2 Entrada dos eventos**
- Ampliar a API de eventos do dispositivo (`POST /devices/{id}/events`, chave do dispositivo) com campos opcionais: `identityRef`, `method`, `direction`.
  O adaptador `SIMULATOR` e os testes usam isso desde já.
- `DahuaEventMapper`: extrair a referência da pessoa do evento `AccessControl` (`CardNo`/`UserID` fora do nosso comando) como **campo opcional**, com teste
  e a ressalva de que os nomes de campo **só serão confirmados em campo** (B6, roteiro em `docs/reference-analysis/DAHUA_SOURCES.md`). Sem confirmação,
  o recurso funciona com o simulador e fica marcado como "não validado em equipamento".
- **Contingência (agente local):** os eventos offline também precisam gerar o registro quando sincronizam; entra como etapa própria (a sincronização é
  idempotente) e fica listada como limite até ser feita.

### Fase 2 — API e tela "Meus acessos"
- `GET me/accesses?from=&to=&page=` (morador; **só os dele**, outro morador e outro condomínio recebem 404; sem token 401): data, hora, `IN/OUT/UNKNOWN`,
  ponto de acesso, meio, resultado. Período máximo de 31 dias por consulta, paginado.
- `GET me/accesses/last`: último acesso (para o cartão da tela inicial).
- **Auditoria de cada consulta** (`access.history.view.self`; o registro não guarda o conteúdo).
- **App do morador:** item "Meus acessos" em "Mais" e cartão "Último acesso" no Início. Lista agrupada por dia, com ícone de entrada, saída ou passagem,
  filtros Hoje, 7 dias e 30 dias, estado vazio honesto e a frase "Só você vê esta lista". Mostra "passagem" onde o sentido é desconhecido.
- **Titular dos dados:** exportação e anonimização (`PrivacyService`) passam a incluir os acessos e a foto; anonimizar **apaga** a foto e os eventos pessoais.
- **Retenção:** expurgo no `RetentionService` (D19, padrão 90 dias).

### Fase 3 — Foto de cadastro e consentimento (recurso desligado por padrão: `RESIDENT_PHOTO_ENABLED=false`)
- **`privacy_consent`:** `id`, `condominium_id`, `resident_id`, `purpose` (`ACCESS_PHOTO`), `text_version`, `granted_at`, `revoked_at`, `granted_by_user_id`.
  Consentimento **específico e destacado**, versão do texto registrada, revogável. Revogar **apaga a foto na hora** e não bloqueia a entrada da pessoa.
- **`resident_photo`:** `resident_id` (única), `condominium_id`, `content` **cifrado** (AES-GCM, mesmo padrão da imagem da entrada), `captured_at`, `uploaded_by`,
  `consent_id`. Sem URL pública; baixada com o token; `Cache-Control: no-store`.
- **API:** `POST me/consents` e `POST me/consents/{id}/revoke`; `PUT me/photo` (exige consentimento ativo; limite de 2 MB; recompressão para no máximo 640 px
  e 200 KB com o `SnapshotImages` movido para um pacote compartilhado); `GET me/photo`; `DELETE me/photo` (exclusão efetiva). Cada uso na auditoria.
- **Qualidade da foto, sem prometer o que não existe:** validamos formato, tamanho, proporção e dimensão mínima. **Não há detecção de rosto** (exigiria uma
  biblioteca de visão computacional, a decidir e licenciar); a tela diz "use uma foto de rosto, de frente, sem óculos escuros". Fica registrado como limite.
- **Quem vê a foto:** o titular. A portaria e o síndico **não** veem a imagem na primeira versão (D17); a ficha do morador mostra só "foto cadastrada: sim ou não".
- **Dependentes e menores:** **bloqueados** nesta versão (D17). O responsável não envia foto de dependente.
- **App:** tela "Sua foto de cadastro" com a **tela de transparência** (finalidade única: controle de acesso; por quanto tempo; quem vê; como revogar; que existe
  alternativa sem biometria), o aceite, o envio (câmera ou galeria) e "Remover minha foto". A foto aparece em "Meus acessos".

### Fase 4 — Ganchos para a identificação facial (sem implementar)
- Reservar `method = FACE` e `access_identity.kind = FACE_REF` (referência do cadastro **dentro do terminal**), sem criar gabarito na central.
- Capacidade futura `ENROLL_FACE` no contrato de adaptadores: enviar a foto ao terminal para ele gerar o gabarito localmente. **Só depois de D16 a D18**, do RIPD
  e de teste com equipamento real. Nesta entrega o contrato apenas **não impede** isso.

### Fase 5 — Provas e documentação
- **Backend (IT):** morador só vê os próprios acessos (outro morador da mesma unidade, de outra unidade e de outro condomínio: 404 ou lista vazia, nunca vazamento);
  portaria e síndico não leem o histórico pessoal; sentido `UNKNOWN` aparece como passagem; evento de referência desconhecida não grava registro pessoal; ingestão
  com chave errada recusada; consulta auditada; período inválido recusado; expurgo por retenção; exportar e anonimizar incluem acessos e foto.
  Foto: exige consentimento ativo; revogar apaga; conteúdo cifrado em repouso; `no-store`; outro morador não lê; recurso desligado devolve 409.
- **Telas:** testes do app (lista, filtros, passagem, estado vazio, consentimento, envio, remoção).
- **Navegador real:** E2E com o dispositivo simulado emitindo entrada e saída do morador de teste; o morador vê os horários; fluxo de consentimento e foto
  sintética (nunca foto real nos testes).
- Esquema mudou: `restore-drill`. Ingestão de eventos em volume: `load-test` com um cenário novo (muitos eventos por minuto).
- Atualizar `docs/lgpd.md` (finalidade, retenção, quem vê), `docs/STATUS.md`, `pendencias.md`, `docs/product-scope.md`, `docs/security.md`.

## 5. Ordem
| Passo | Entrega | Depende de |
|---|---|---|
| 1 | Fase 1 (modelo, entrada de eventos pelo simulador, Dahua como campo opcional) | nada |
| 2 | Fase 2 ("Meus acessos" + exportação e retenção) | passo 1; D19 para o prazo final |
| 3 | Fase 3 (foto e consentimento), **desligada por padrão** | **D16** para ligar em produção; D17 para dependentes e visão da portaria |
| 4 | Contingência: eventos offline do agente viram acessos | passo 1 |
| 5 | Validação em campo (campos do evento, sentido) | equipamento real |
| 6 | Fase 4 (identificação facial) | D16 a D18, RIPD, terminal facial |

Cada passo termina com testes, `mvn verify`, documentos atualizados e commit; push só com a sua autorização.

## 6. Riscos e limites declarados
- **Dado de rotina e localização:** o histórico de entradas e saídas é pessoal por natureza; por isso a primeira versão mostra **só ao titular** e tem retenção curta.
- **Campos do evento do equipamento não confirmados (B6):** sem equipamento, a identificação da pessoa só é provada com o simulador.
- **Sentido (entrada ou saída):** sem leitor nos dois sentidos, o sistema mostra "passagem". Não há "quem está dentro agora".
- **Unidade compartilhada:** moradores da mesma unidade **não** veem os acessos uns dos outros (D17).
- **Sem detecção de rosto nem gabarito:** a foto é só uma imagem cadastral; a qualidade depende de quem envia.
- **Consentimento sozinho não basta** (ver as perguntas ao jurídico em `pendencias.md`): a alternativa sem biometria e a revogação eficaz são parte do desenho.
- **Captura de tela** da foto ou do histórico pelo morador não tem como ser impedida.
- **Não declaro conformidade com a LGPD**; o parecer jurídico vem antes de ligar a foto em produção.
