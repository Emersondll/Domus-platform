# Status do projeto (2026-10-20)

_Fases 0–4 concluídas: 36 testes de integração no backend (`mvn verify`) e 4 testes de UI no painel (`npm test`)._

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

## Simulado (somente dev)
- Emissor de tokens `/dev/token` e dados semeados fictícios (`DevSeed`). Substituem Keycloak.

## Pendente (próximas fases)
- Fase 2: Keycloak + MFA no Compose, rate limiting, CI, métricas/OpenTelemetry, escopo de ordenação seguro nas listagens.
- Fase 5: app do morador.
- Fase 6: adaptadores de dispositivos (ONVIF/RTSP/SIP) e simuladores. Fase 7: agente local de contingência.
- Fase 8: observabilidade, backup/restauração, retenção LGPD, testes de carga.
- Skills próprias: `multi-tenant-security`, `access-control-resilience`, `device-integration`.

## Limitações conhecidas
- `sort` inválido responde 400, mas ainda não há lista branca de campos ordenáveis.
- Sem MFA, sem rate limiting, sem criptografia de campos pessoais ainda.
- Nada foi testado com hardware real.
