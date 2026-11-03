# Documentos jurídicos — RASCUNHOS para revisão de advogado

> **Isto NÃO é parecer jurídico e NÃO declara conformidade com a LGPD.** São rascunhos técnicos escritos a partir do que o sistema **de fato faz hoje**
> (código e configuração do repositório), para que um advogado especializado em proteção de dados os revise, corrija e aprove. Nada aqui foi
> validado por jurista. Trechos entre `[COLCHETES]` são lacunas que só o controlador (condomínio) ou o titular do produto podem preencher.
> Citações de artigos da LGPD (Lei 13.709/2018) estão marcadas **para conferência**: o advogado deve confirmar cada uma.

Data dos rascunhos: 2026-11-03. Fonte dos fatos: `docs/lgpd.md`, `docs/security.md`, `application*.yml`, `deploy/keycloak/portaria-realm.json`.

## Conteúdo
| Arquivo | Para quê | Decisões que destrava |
|---|---|---|
| [`perguntas-ao-advogado.md`](perguntas-ao-advogado.md) | Lista objetiva do que decidir, com a recomendação técnica e a consequência de cada resposta | D1, D16, D18, D19, D21, CR6, T11 |
| [`inventario-de-dados.md`](inventario-de-dados.md) | Que dado pessoal o sistema trata, onde fica, quem acessa e por quanto tempo | D1 (base para o RIPD) |
| [`retencao-e-descarte.md`](retencao-e-descarte.md) | Prazos praticados hoje, o que nunca expira e o que depende de decisão | D1, D19, T11 |
| [`politica-de-privacidade-rascunho.md`](politica-de-privacidade-rascunho.md) | Texto para o morador (app) | D1, D16 |
| [`aviso-de-cameras-rascunho.md`](aviso-de-cameras-rascunho.md) | Placa na entrada e texto no app sobre as câmeras | D21 |
| [`termo-de-ciencia-e-consentimento-rascunho.md`](termo-de-ciencia-e-consentimento-rascunho.md) | Ciência do morador (câmeras) e consentimento específico da foto de cadastro | CR6, D16 |
| [`contrato-controlador-operador-checklist.md`](contrato-controlador-operador-checklist.md) | O que o contrato condomínio ↔ empresa de portaria ↔ plataforma precisa tratar | D1 |

## Como usar
1. Entregue esta pasta ao advogado junto com `docs/lgpd.md`. Peça que responda `perguntas-ao-advogado.md` por escrito.
2. Só depois da aprovação: ajustar os textos, **publicar** a política no app e, onde couber, registrar a ciência/consentimento no sistema (hoje **não** é registrada, ver CR6).
3. Enquanto isso, mantenha desligados `RESIDENT_PHOTO_ENABLED` e `SNAPSHOTS_ENABLED` e não libere câmeras ao morador em produção.
