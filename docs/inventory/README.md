# Inventário de dependências e licenças

Regenerado em 2026-10-22 a partir das próprias ferramentas de build.

| Arquivo | Conteúdo |
|---|---|
| `maven-backend-THIRD-PARTY.txt` | 155 dependências de runtime do backend (`license-maven-plugin`) |
| `maven-agent-THIRD-PARTY.txt` | 6 dependências do agente |
| `backend-jars-without-license-files.txt` | 42 jars do backend sem arquivo de licença próprio, com licença e página do projeto |
| `Apache-2.0.txt`, `EPL-2.0.txt`, `MIT-notices.txt` | textos de licença que acompanham os artefatos |
| `npm-production-licenses.txt` | dependências npm que entram nos bundles (MIT/ISC e hls.js, Apache-2.0) |

Resumo: Apache-2.0, MIT, BSD-2/3, EPL-2.0/EDL-1.0 e CC0. Itens com GPL/LGPL no relatório são **duplo licenciamento** com opção permissiva
(logback: EPL-2.0 ou LGPL-2.1 — usamos EPL-2.0; jakarta.annotation/transaction: EPL-2.0 ou GPL-2.0 com Classpath Exception).
Nenhuma dependência AGPL/SSPL. Imagens de contêiner (postgres, keycloak, caddy, prometheus, grafana, temurin) têm licenças próprias
(PostgreSQL, Apache-2.0, Apache-2.0, Apache-2.0, AGPL-3.0 para o Grafana OSS — usado como serviço separado, sem modificação,
e **precisa de revisão jurídica** se o produto for oferecido a terceiros como serviço).

Código de projetos de referência: **nenhum foi copiado** (ver `docs/reference-analysis/LICENSES.md`).
**Licença do próprio projeto:** Apache 2.0 (ver `LICENSE` e `THIRD_PARTY_NOTICES.md` na raiz).
Atualize com: `mvn -f backend/pom.xml org.codehaus.mojo:license-maven-plugin:2.5.0:add-third-party` e `npm ls --omit=dev`.
