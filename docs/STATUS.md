# Status do projeto (2026-10-20)

## Concluído e verificado
- Build Maven (Java 26, Spring Boot 4.0.7); `mvn verify`: 15 testes de integração passando contra PostgreSQL 17 real
  (Testcontainers).
- Migração Flyway V1: tenancy, usuários/associações, auditoria append-only (trigger bloqueia UPDATE/DELETE).
- API v1: empresas, condomínios, blocos, unidades, moradores (paginada, validada, erros RFC 9457, OpenAPI).
- Isolamento: acesso cruzado entre empresas/condomínios (404), operador limitado aos condomínios atribuídos,
  permissão de gestão, FK composta no banco, auditoria de negações.
- Execução local manual verificada: Compose + `spring-boot:run` + chamadas HTTP.

## Simulado (somente dev)
- Emissor de tokens `/dev/token` e dados semeados fictícios (`DevSeed`). Substituem Keycloak.

## Pendente (próximas fases)
- Fase 2: Keycloak + MFA no Compose, rate limiting, CI, métricas/OpenTelemetry, escopo de ordenação seguro nas listagens.
- Fase 3: visitantes, veículos, encomendas, reservas, credenciais/QR com validade e uso único (+ teste de concorrência).
- Fase 4: central de operadores (painel web em TypeScript). Fase 5: app do morador.
- Fase 6: adaptadores de dispositivos (ONVIF/RTSP/SIP) e simuladores. Fase 7: agente local de contingência.
- Fase 8: observabilidade, backup/restauração, retenção LGPD, testes de carga.
- Skills próprias: `multi-tenant-security`, `access-control-resilience`, `device-integration`.

## Limitações conhecidas
- O parâmetro `sort` enviado pelo cliente não é restrito a uma lista de campos permitidos.
- Sem MFA, sem rate limiting, sem criptografia de campos pessoais ainda.
- Nada foi testado com hardware real.
