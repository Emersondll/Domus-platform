# Escopo funcional do produto: o que um sistema de portaria precisa ter

Referência funcional: lista pública de recursos de produtos de portaria do mercado (informada em 2026-10-23). Serve só para **mapear módulos**.
O Domus não usa código, telas, textos, marca nem APIs privadas de nenhum concorrente, e **não promete paridade nem compatibilidade** com eles.
Recursos que dependem de equipamento, fabricante ou lei ficam marcados como **externo**.

Legenda: **Feito** (com testes) · **Parcial** · **Falta** (dá para fazer só com software) · **Externo** (depende de equipamento, contrato ou decisão jurídica).

## 1. Controle de acesso
| Recurso | Situação | Observação |
|---|---|---|
| Cadastro de moradores (titular, cônjuge, dependentes), animais, visitantes e prestadores | Feito | Painel (seção Cadastros, botão no cabeçalho) e app do morador (Minha casa); visitantes via convite ou pedido de autorização no balcão |
| Pré-autorização de visitantes (QR Code, validade, usos) | Feito | Visitante recorrente = convite com vários usos |
| Autorização de entrada pelo celular (visitante sem convite) | **Feito** | Operador pede, morador aprova ou nega no app (prazo de 5 min); a resposta não abre a porta |
| Registro de entradas **e saídas** | **Feito** | A portaria registra a saída; a tela mostra quem está no local |
| Gestão de veículos | Feito | Placa cifrada, índice cego |
| Histórico de acessos | Feito | Sem fotos |
| Imagem da câmera de entrada junto do aviso de visita | **Feito (câmera simulada)** | Desligado por padrão; cifrada, com retenção e auditoria. Câmera real e base legal (D13) pendentes; ver `docs/plano-cameras-visitas.md` |
| Histórico com **fotos** de todo acesso | Externo | Exige política de retenção de imagem (LGPD) |
| Notificações de visita e chegada | Parcial | In-app; push/e-mail/WhatsApp são externos (D5) |

## 2. Aplicativo do morador
| Recurso | Situação | Observação |
|---|---|---|
| Notificações de visitantes e retorno da chamada | Feito | No app do morador as notificações são separadas por assunto (Visitas, Encomendas, Avisos, Mensagens, Portaria), cada aba com a contagem de não lidas |
| Portaria avisa chegada de visita conhecida ou prestador (com empresa) | Feito | Aba Visitantes; o morador recebe a notificação na aba Visitas |
| Encomendas a retirar na portaria (contagem, aba com número, lembrete ao morador) | Feito | Lista filtrada por status no servidor |
| Entrada e saída de visitante/prestador autorizado pelo morador (conta como "no local" e reduz ao registrar a saída) | Feito | Antes só convite validado entrava na contagem; ver V14 |
| Voltar ao início em todas as telas (app do morador e painel) | Feito | |
| Aprovar/negar visitante pelo celular | **Feito** | |
| Abertura remota de portão pelo morador | **Feito** | Desligada por padrão; duas travas (política do condomínio, ligada pelo síndico, e porta marcada); 1 abertura a cada 30 s por morador; mesma regra "aceito ≠ aberto" |
| Interfone móvel (voz/vídeo) | Externo | SIP/WebRTC e servidor de mídia |
| Consulta de registros de acesso | Feito | |
| Comunicação com portaria, zelador e síndico | Parcial | Comunicados para todos os moradores: **feito**. Mensagens individuais (conversa morador ↔ portaria, texto cifrado, limite de 10/min): **feito**; sem push/e-mail/WhatsApp e sem anexos |

## 3. Correspondências e entregas
| Recurso | Situação | Observação |
|---|---|---|
| Registro, notificação, controle de retirada | Feito | |
| Comprovante/ticket imprimível | **Feito** | Impressão pelo navegador; impressora térmica é externo |

## 4. Segurança e automação
| Recurso | Situação | Observação |
|---|---|---|
| Integração com equipamentos | Parcial | Dahua/Intelbras (não validado em campo), ONVIF (só consulta), simulador, relé do agente |
| Câmeras: lista e estado | Feito | |
| Câmeras: vídeo ao vivo (RTSP → HLS pelo MediaMTX) | Feito (provado só com fonte RTSP de teste) | Câmera real, câmera atrás de NAT (o agente ainda não publica o fluxo) e ONVIF `GetStreamUri` seguem pendentes; ver `docs/cameras.md` |
| Abertura automatizada de acessos | Parcial | Comando físico com confirmação por evento; automação por regra (lote C) |
| Alerta de pânico | **Feito** | Botão com confirmação no app; entra na fila com prioridade e abre incidente de severidade alta. **Na central: janela de alerta em tela cheia com a unidade de origem e sirene (Web Audio), até alguém atender; o operador decide atender (abre o turno se preciso) ou silenciar o som.** O som depende de o navegador permitir áudio (há botão "Ativar som"). Botão físico é externo |
| "Meus acessos" do morador (entradas e saídas dele) | **Feito com leitores simulados** | Registro só de pessoas cadastradas, sentido pelo ponto de acesso (senão "passagem"), só o titular vê, consulta auditada, retenção de 90 dias. **Não validado com equipamento real** (campos do evento Dahua = suposição) e o agente local ainda não envia passagens de moradores. Ver `docs/plano-meus-acessos.md` |
| Foto de cadastro do morador | **Feito, desligado por padrão** | Cifrada, consentimento específico e revogável (revogar apaga), só o titular vê; sem detecção de rosto; dependentes bloqueados. Ligar em produção depende de D16 (parecer jurídico). Não vira gabarito biométrico sem D16 a D18 |
| Reconhecimento facial, biometria, placas (LPR) | Externo | Equipamento + base legal LGPD (dado sensível, art. 11), RIPD e alternativa sem biometria; não se implementa sem decisão (D16 a D18) |
| Portaria virtual / remota | Feito | Central de operadores multicondomínio, fila, turnos, contingência offline |

## 5. Gestão administrativa
| Recurso | Situação | Observação |
|---|---|---|
| Moradores e unidades | Feito | Telas do síndico/portaria feitas (seção Cadastros, botão no cabeçalho) |
| Reserva de áreas comuns | Feito | |
| Livro de ocorrências | **Feito** | Ocorrências + livro de turno (registros e passagens, texto cifrado) |
| Comunicados internos | **Feito** | Publica no painel; chega como aviso no app |
| Documentos | Lote C | Exige decisão de armazenamento de arquivos |
| Relatórios de acessos | **Feito** | Tela por período (máx. 31 dias) e CSV (máx. 5.000 linhas, auditado, com proteção contra injeção de fórmula) |
| Medições de água e energia | Lote C | |
| Assembleias virtuais | Externo | Exige vídeo e validade jurídica de votação |

## 6. Plataforma multi-tenant e planos
| Recurso | Situação | Observação |
|---|---|---|
| Vários condomínios, isolamento, perfis, auditoria | Feito | |
| Planos comerciais (recursos liberados por plano) | Lote C | Mecanismo de liberação por condomínio; **valores e regras do plano são decisão de negócio (D7)** |
| Telas de administração (síndico) | **Feito** | Aba Cadastros |
| Telas de administração (plataforma e empresa operadora) | Falta | Empresas e administradores, agentes, rotação de chave, conflitos de sincronização (só API) |

## Ordem
- **Lote A (feito em 2026-10-23):** autorização do visitante pelo celular e botão de pânico.
- **Lote B (feito; mensagens individuais em 2026-10-25):** saída de visitantes, relatórios de acesso e CSV, comunicados, livro de turno, comprovante imprimível, abertura remota pelo morador (opcional por condomínio). Mensagens individuais morador ↔ portaria incluídas.
- **Lote C:** liberação de recursos por plano, documentos, medições, regras de automação.
- **Externo:** vídeo, interfone, fotos, facial/biometria/placas, assembleias, push/e-mail/WhatsApp.
