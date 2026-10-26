# Avisos de terceiros

O código próprio deste repositório é licenciado sob a [Licença Apache 2.0](LICENSE). Os componentes abaixo mantêm as licenças originais.

| Componente | Licença | Onde está o detalhe |
|---|---|---|
| Dependências Maven do backend (155) e do agente (6) | Apache-2.0, MIT, BSD-2/3, EPL-2.0/EDL-1.0, CC0 (logback e jakarta.*: usamos a opção EPL-2.0) | `docs/inventory/maven-*-THIRD-PARTY.txt` |
| Dependências npm que entram nos bundles | MIT, ISC; hls.js (painel) Apache-2.0 | `docs/inventory/npm-production-licenses.txt` |
| Imagens de contêiner usadas no deploy (PostgreSQL, Keycloak, Caddy, Prometheus, Grafana, Temurin) | Licenças próprias de cada projeto; **o Grafana OSS é AGPL-3.0** | `docs/inventory/README.md` |

As imagens de contêiner **não fazem parte** deste repositório nem são redistribuídas por ele: são baixadas na hora do deploy e rodam como serviços
separados, sem modificação. Se o produto for oferecido a terceiros como serviço, o uso do Grafana (AGPL-3.0) precisa de revisão jurídica.

Nenhum código dos repositórios de referência foi copiado (ver `docs/reference-analysis/LICENSES.md`).

## Onde os avisos acompanham o que é distribuído
- **Agente (jar):** `META-INF/domus/` traz `LICENSE`, este arquivo e o inventário; o `NOTICE` das dependências Apache é consolidado pelo shade.
- **Backend (jar):** `META-INF/domus/` com os mesmos arquivos; cada dependência mantém a própria licença dentro do jar aninhado.
- **Web (painel e app do morador):** `/licenses.txt` em cada bundle, gerado no build por `web/scripts/gen-licenses.mjs` com o texto de licença de cada dependência de produção.
- Textos completos: `docs/inventory/Apache-2.0.txt`, `EPL-2.0.txt` e `MIT-notices.txt`. Os 42 jars do backend sem arquivo de licença próprio estão listados, com licença e página do projeto, em `docs/inventory/backend-jars-without-license-files.txt`.

## Marcas de terceiros
Dahua, Intelbras, ONVIF, Keycloak, Grafana, Prometheus, Caddy, PostgreSQL e demais nomes citados nesta documentação e no código
pertencem aos respectivos titulares e aparecem **apenas para identificar produtos e protocolos compatíveis** (uso nominativo).
Este projeto **não tem afiliação, parceria, certificação ou endosso** de nenhum deles. Em especial, o adaptador `ONVIF` implementa
apenas o necessário para consultar equipamentos e **não declara conformidade a nenhum perfil ONVIF**; o adaptador `DAHUA_CGI` foi
escrito a partir de comportamento observável e relatos públicos e **não é homologado** pela Dahua nem pela Intelbras.
