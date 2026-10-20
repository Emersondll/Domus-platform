# ADR 0003 — Autenticação por OIDC, autorização por associações

**Status:** aceita (parcial). A API é OAuth2 Resource Server (JWT). Em produção o emissor será Keycloak
(`OIDC_ISSUER_URI`), com MFA para operadores e administradores. Papéis e escopos **não** vêm do token: vêm de
`membership`, de forma que revogar acesso é imediato. No perfil `dev` um emissor local em memória substitui o
Keycloak apenas para validação. **Pendente:** integrar o Keycloak no Compose, MFA, rate limiting, bloqueio por força bruta.
