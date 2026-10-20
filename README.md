# Portaria — plataforma de portaria virtual multicondomínio

Monólito modular Java (Spring Boot 4.0.7, Java 26) para empresas que operam portaria remota de vários condomínios.
Estado atual: **Fase 2 (fundação executável)**. Veja [docs/STATUS.md](docs/STATUS.md) para o que está concluído,
simulado e pendente.

## Rodar localmente

Requisitos: JDK 26, Maven 3.9.9, Docker.

```bash
cp .env.example .env                       # opcional; padrões de dev já funcionam
docker compose up -d postgres              # PostgreSQL 17 na porta 5433
cd backend
DB_PASSWORD=portaria_dev_only SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run   # API na porta 8081
```

O perfil `dev` semeia dados **fictícios** (2 empresas, 3 condomínios) e expõe `/dev/token` para emitir tokens de teste.
Esse endpoint não existe fora do perfil `dev`.

```bash
TOKEN=$(curl -s "localhost:8081/dev/token?subject=alpha-admin" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -H "Authorization: Bearer $TOKEN" localhost:8081/api/v1/condominiums
```

Usuários de teste: `platform-admin`, `alpha-admin`, `beta-admin`, `operator-sol`, `operator-multi`, `manager-sol`.
Documentação interativa da API: <http://localhost:8081/swagger-ui.html>.

## Painel da central (web)

```bash
cd web && npm install && npm run dev -w panel      # http://localhost:5173 (proxy para a API em 8081)
```

## App do morador (PWA)

```bash
cd web && npm run dev -w resident      # http://localhost:5174 ; entra como o morador de teste `resident-sol`
```

## Agente local de contingência

Ver [docs/agent.md](docs/agent.md). Prova ponta a ponta (central e agente reais, queda forçada):
`mvn -f backend/pom.xml package -DskipTests && mvn -f agent/pom.xml package && ./scripts/e2e-contingency.sh`

## Testes

```bash
cd backend && mvn verify      # usa Testcontainers (PostgreSQL real); precisa do Docker
cd web && npm test            # testes de UI (Vitest)
cd agent && mvn test          # agente local (16 testes, sem Docker)
```

## Estrutura

```
backend/   API (pacotes por domínio: shared, identity, commercial, residents, access, operations, ...)
web/       painel da central e app do morador (React + TypeScript)
agent/     agente local de contingência (Java, SQLite)
docs/      análise dos repositórios de referência, arquitetura, ADRs, status
```
