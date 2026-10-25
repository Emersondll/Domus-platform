# Escopo funcional do produto: o que um sistema de portaria precisa ter

Referência funcional: lista pública de recursos de produtos de portaria do mercado (informada em 2026-10-23). Serve só para **mapear módulos**.
O Domus não usa código, telas, textos, marca nem APIs privadas de nenhum concorrente, e **não promete paridade nem compatibilidade** com eles.
Recursos que dependem de equipamento, fabricante ou lei ficam marcados como **externo**.

Legenda: **Feito** (com testes) · **Parcial** · **Falta** (dá para fazer só com software) · **Externo** (depende de equipamento, contrato ou decisão jurídica).

## 1. Controle de acesso
| Recurso | Situação | Observação |
|---|---|---|
| Cadastro de moradores, visitantes e prestadores | Feito | Moradores por API/painel; visitantes via convite |
| Pré-autorização de visitantes (QR Code, validade, usos) | Feito | Visitante recorrente = convite com vários usos |
| Autorização de entrada pelo celular (visitante sem convite) | **Feito** | Operador pede, morador aprova ou nega no app (prazo de 5 min); a resposta não abre a porta |
| Registro de entradas **e saídas** | **Feito** | A portaria registra a saída; a tela mostra quem está no local |
| Gestão de veículos | Feito | Placa cifrada, índice cego |
| Histórico de acessos | Feito | Sem fotos |
| Histórico com **fotos** | Externo | Exige câmera e política de retenção de imagem (LGPD) |
| Notificações de visita e chegada | Parcial | In-app; push/e-mail/WhatsApp são externos (D5) |

## 2. Aplicativo do morador
| Recurso | Situação | Observação |
|---|---|---|
| Notificações de visitantes e retorno da chamada | Feito | |
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
| Câmeras: vídeo ao vivo (RTSP/ONVIF) | Externo | Câmeras e servidor de mídia |
| Abertura automatizada de acessos | Parcial | Comando físico com confirmação por evento; automação por regra (lote C) |
| Alerta de pânico | **Feito** | Botão com confirmação no app; entra na fila com prioridade e abre incidente de severidade alta. Botão físico é externo |
| Reconhecimento facial, biometria, placas (LPR) | Externo | Equipamento + base legal LGPD (dado sensível); não se implementa por software próprio sem decisão |
| Portaria virtual / remota | Feito | Central de operadores multicondomínio, fila, turnos, contingência offline |

## 5. Gestão administrativa
| Recurso | Situação | Observação |
|---|---|---|
| Moradores e unidades | Feito | Falta tela de administração (pendência 5) |
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
| Telas de administração (plataforma, empresa, síndico) | Falta | Pendência 5 |

## Ordem
- **Lote A (feito em 2026-10-23):** autorização do visitante pelo celular e botão de pânico.
- **Lote B (feito; mensagens individuais em 2026-10-25):** saída de visitantes, relatórios de acesso e CSV, comunicados, livro de turno, comprovante imprimível, abertura remota pelo morador (opcional por condomínio). Mensagens individuais morador ↔ portaria incluídas.
- **Lote C:** liberação de recursos por plano, documentos, medições, regras de automação.
- **Externo:** vídeo, interfone, fotos, facial/biometria/placas, assembleias, push/e-mail/WhatsApp.
