# Avisos de terceiros

O código próprio deste repositório é licenciado sob a [Licença MIT](LICENSE). Os componentes abaixo mantêm as licenças originais.

| Componente | Licença | Onde está o detalhe |
|---|---|---|
| Dependências Maven do backend (154) e do agente (6) | Apache-2.0, MIT, BSD-2/3, EPL-2.0/EDL-1.0, CC0 (logback e jakarta.*: usamos a opção EPL-2.0) | `docs/inventory/maven-*-THIRD-PARTY.txt` |
| Dependências npm que entram nos bundles | MIT, ISC | `docs/inventory/npm-production-licenses.txt` |
| Imagens de contêiner usadas no deploy (PostgreSQL, Keycloak, Caddy, Prometheus, Grafana, Temurin) | Licenças próprias de cada projeto; **o Grafana OSS é AGPL-3.0** | `docs/inventory/README.md` |

As imagens de contêiner **não fazem parte** deste repositório nem são redistribuídas por ele: são baixadas na hora do deploy e rodam como serviços
separados, sem modificação. Se o produto for oferecido a terceiros como serviço, o uso do Grafana (AGPL-3.0) precisa de revisão jurídica.

Nenhum código dos repositórios de referência foi copiado (ver `docs/reference-analysis/LICENSES.md`).
