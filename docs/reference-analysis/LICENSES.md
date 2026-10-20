# Análise dos repositórios de referência e licenças

Data da análise: 2026-10-20. Clones rasos dos forks `Emersondll/*`; a autoria e a licença são dos projetos originais.

| Projeto | Original | Licença | Pode reutilizar código? |
|---|---|---|---|
| portaria.live | rafaelbozzetti/portaria.live | MIT (c) 2019 Rafael Bozzetti | Sim, com aviso de copyright. Não há interesse técnico (PHP 7, Slim 3, sem testes). |
| Portarius | pescador95/portarius | MIT (c) 2025 Iédio João Carabolante Júnior | Sim, com aviso. Está em Go, então só vale como referência de requisitos. |
| Porteiro-Virtual-com-IA | juliaomedeiros/Porteiro-Virtual-com-IA | **Sem arquivo de licença** | **Não.** Sem licença, todos os direitos são reservados. Usar apenas ideias/conceitos. |

**Decisão:** este produto não copia código dos três. Usa-os como fonte de requisitos e fluxos. Se algum trecho
MIT vier a ser incorporado, o aviso de copyright será preservado em `THIRD_PARTY_NOTICES`.

## Matriz de evidências

| Requisito | Projeto | Evidência | Decisão |
|---|---|---|---|
| Blocos/unidades/moradores | portaria.live | Migrations e models `Block`, `Unit`, `Person` | Desenvolver (modelo próprio, multi-tenant) |
| Visitantes + log de acesso | portaria.live | `Visitor`, `VisitorLog`, `access_log`; painel do operador | Desenvolver na Fase 3/4 |
| Veículos | portaria.live, Portarius | Models `Vehicle`; inventário de veículos e pets | Desenvolver na Fase 3 |
| Encomendas + lembretes | Portarius | Módulos `package` e `reminder` (Go) | Desenvolver (só o fluxo) |
| Reservas de espaços | Portarius, Porteiro-IA | Módulo `reservation`; `booking_service` com controle de concorrência | Desenvolver |
| Importação por CSV | Portarius | Serviços `*_import_service` | Desenvolver (desejável) |
| Atendimento automatizado (RAG) | Porteiro-IA | `rag_service` com filtro por `condominio_id`, pgvector, transbordo | Fase futura; conceito apenas |
| Multi-tenant | nenhum completo | Porteiro-IA filtra por condomínio; os outros não | **Desenvolver** (feito na Fase 2) |
| Controle físico de acesso | nenhum | Ausente nos três | **Desenvolver** (Fase 6) |
| Vídeo/SIP | nenhum | Ausente | **Desenvolver** (Fase 6) |
| Contingência local | nenhum | Ausente | **Desenvolver** (Fase 7) |
| Central de operadores | nenhum | portaria.live tem só painel de registro | **Desenvolver** (Fase 4) |

## Riscos observados nas referências (não replicar)

- Portarius: `jwt.Parse` não valida o método de assinatura (risco de confusão de algoritmo); tags GORM malformadas;
  bloco/apartamento como texto de tamanho fixo (1 e 2 caracteres).
- portaria.live: stack sem manutenção desde 2021; sem isolamento entre condomínios.
- Porteiro-IA: sem licença; poucos commits (11); criptografia citada no README não foi validada.
