# Status do projeto (2026-10-20)

_Fases 0–7 concluídas: 65 testes no backend (`mvn verify`), 16 no agente, 5 de UI no painel e 4 no app do morador; mais a prova ponta a ponta `scripts/e2e-contingency.sh`._

## Concluído e verificado
- Build Maven (Java 26, Spring Boot 4.0.7); `mvn verify`: 15 testes de integração passando contra PostgreSQL 17 real
  (Testcontainers).
- Migração Flyway V1: tenancy, usuários/associações, auditoria append-only (trigger bloqueia UPDATE/DELETE).
- API v1: empresas, condomínios, blocos, unidades, moradores (paginada, validada, erros RFC 9457, OpenAPI).
- Isolamento: acesso cruzado entre empresas/condomínios (404), operador limitado aos condomínios atribuídos,
  permissão de gestão, FK composta no banco, auditoria de negações.
- Execução local manual verificada: Compose + `spring-boot:run` + chamadas HTTP.

- Fase 3: veículos, visitantes, encomendas, áreas comuns/reservas e credenciais (convites com QR).
  Uso único garantido por UPDATE atômico (teste: 20 validações simultâneas → 1 autorização); reservas sem
  sobreposição por exclusion constraint (teste concorrente: 201 + 409); token HMAC amarrado ao condomínio,
  sem dado pessoal; tentativas registradas; papel OPERATE para a rotina da portaria.
  GRANTED = autorização válida e uso consumido; **não** significa porta aberta (Fase 6).

- Fase 4: central de operações. Turnos (um aberto por usuário), fila de atendimentos com atribuição atômica
  (`FOR UPDATE SKIP LOCKED`; teste: 10 pegadas concorrentes por 2 operadores, sem duplicidade), transferência só
  para operador de plantão do mesmo condomínio, justificativa obrigatória para ENTRY_GRANTED/ENTRY_DENIED,
  histórico de decisões, incidentes, visão geral. Painel web (React + TS) em `web/panel`.
  Saúde de dispositivos e conectividade aparecem como "não integrado"; o botão "Abrir porta" está desabilitado.

- Fase 5: app do morador (PWA, `web/resident`): convites com QR (uso e validade definidos, revogação), encomendas,
  reservas (conflito explicado), veículos, pedido de atendimento à central, perfil/contato, preferências de
  privacidade e histórico de acessos da própria unidade. Notificações in-app geradas por encomenda e entrada de
  visitante, respeitando a preferência de cada morador e isoladas por unidade. Ver ADR 0004.
  **Não integrados:** push, e-mail e WhatsApp (API oficial); sem NFC/credencial em carteira.

- Fase 6: dispositivos. Contrato `DeviceAdapter`, adaptadores SIMULATOR/GENERIC_HTTP/ONVIF, comandos físicos com
  estados verificáveis (ver ADR 0005), idempotência, autorização por atendimento ou credencial, ingestão de eventos
  por chave do dispositivo, anti-SSRF e anti-XXE. O painel abre a porta de verdade e só exibe "porta aberta" com
  confirmação. **Nenhum teste com hardware real.**

- Fase 7: agente local de contingência (`agent/`, ver `docs/agent.md` e ADR 0006): identidade por instalação com chave
  rotacionável, política assinada/versionada/com validade, contingência só offline, fila SQLite persistente, sincronização
  idempotente, reconciliação com conflitos auditáveis, relé de comandos (`AGENT_RELAY`), falha fechada. Os 10 cenários de
  falha exigidos têm teste; `scripts/e2e-contingency.sh` derruba a central real (kill -9) e verifica a reconciliação.
  O painel mostra a conectividade real (ONLINE/OFFLINE e eventos pendentes).

## Simulado (somente dev)
- Emissor de tokens `/dev/token`, dados semeados fictícios e dispositivos `SIMULATOR` (`DevSeed`). Substituem Keycloak e hardware.

## Pendente (próximas fases)
- Fase 2: Keycloak + MFA no Compose, rate limiting, CI, métricas/OpenTelemetry, escopo de ordenação seguro nas listagens.
- Pendente de dispositivos: SIP/telefonia, vídeo ao vivo (RTSP), primeira integração com fabricante real.
- Fase 8: observabilidade, backup/restauração, retenção LGPD, testes de carga.
- Skills próprias: `multi-tenant-security`, `access-control-resilience`, `device-integration`.

## Limitações conhecidas
- `sort` inválido responde 400, mas ainda não há lista branca de campos ordenáveis.
- Sem MFA, sem rate limiting, sem criptografia de campos pessoais ainda.
- Nada foi testado com hardware real (adaptadores validados só contra servidores locais de teste).
