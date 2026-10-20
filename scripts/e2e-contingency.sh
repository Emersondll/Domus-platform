#!/usr/bin/env bash
# Prova ponta a ponta da contingência: central real + agente real; a central é derrubada (kill) e religada.
# Requer: docker compose (postgres), backend e agent já empacotados (mvn package), curl e python3.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
BASE=http://localhost:8081
API=$BASE/api/v1
J='Content-Type: application/json'
export DB_PASSWORD=portaria_dev_only

step() { printf '\n== %s\n' "$*"; }
tok() { curl -s "$BASE/dev/token?subject=$1" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
start_central() {
  SPRING_PROFILES_ACTIVE=dev nohup java -jar "$ROOT/backend/target/platform-0.1.0-SNAPSHOT.jar" >"$WORK/central.log" 2>&1 &
  echo $! >"$WORK/central.pid"
  for _ in $(seq 1 60); do curl -sf "$BASE/actuator/health" >/dev/null && return 0; sleep 1; done
  echo "central did not start"; tail -20 "$WORK/central.log"; exit 1
}
stop_central() { kill -9 "$(cat "$WORK/central.pid")" 2>/dev/null || true; sleep 1; }
cleanup() { stop_central; kill "$(cat "$WORK/agent.pid" 2>/dev/null)" 2>/dev/null || true; }
trap cleanup EXIT
agent_status() { curl -s -H "X-Local-Key: local-secret-key" localhost:8099/status; }
agent_validate() { curl -s -X POST -H "X-Local-Key: local-secret-key" -H "$J" -d "{\"token\":\"$1\",\"entrance\":\"portaria\"}" localhost:8099/validate; }
wait_for() { # descrição, expressão python sobre o status do agente
  for _ in $(seq 1 40); do agent_status | jget "$2" 2>/dev/null | grep -q True && return 0; sleep 1; done
  echo "timeout waiting for: $1"; agent_status; exit 1
}

step "Banco limpo e central no ar"
(cd "$ROOT" && docker compose down -v >/dev/null 2>&1 && docker compose up -d postgres >/dev/null 2>&1)
sleep 8
start_central

MANAGER=$(tok manager-sol); RESIDENT=$(tok resident-sol)
CONDO=$(curl -s -H "Authorization: Bearer $MANAGER" $API/condominiums | jget "d['items'][0]['id']")

step "Cadastro do agente e convite de uso único"
ENROLL=$(curl -s -X POST -H "Authorization: Bearer $MANAGER" -H "$J" -d '{"name":"agente-e2e"}' $API/condominiums/$CONDO/agents)
AGENT_ID=$(echo "$ENROLL" | jget "d['id']"); AGENT_KEY=$(echo "$ENROLL" | jget "d['apiKey']"); POLICY_SECRET=$(echo "$ENROLL" | jget "d['policySecret']")
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ); END=$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)
INV=$(curl -s -X POST -H "Authorization: Bearer $RESIDENT" -H "$J" \
  -d "{\"visitorName\":\"Visita E2E\",\"kind\":\"VISITOR\",\"validFrom\":\"$NOW\",\"validUntil\":\"$END\",\"maxUses\":1}" $API/condominiums/$CONDO/me/invitations)
TOKEN=$(echo "$INV" | jget "d['token']"); INV_ID=$(echo "$INV" | jget "d['id']")

step "Agente no ar (sincroniza política)"
PORTARIA_CENTRAL_URL=$BASE PORTARIA_AGENT_ID=$AGENT_ID PORTARIA_AGENT_KEY=$AGENT_KEY PORTARIA_POLICY_SECRET=$POLICY_SECRET \
PORTARIA_DATA_DIR=$WORK/agent-data PORTARIA_LOCAL_KEY=local-secret-key PORTARIA_LOCAL_PORT=8099 PORTARIA_SYNC_SECONDS=2 PORTARIA_TIMEOUT_SECONDS=2 \
  nohup java -jar "$ROOT/agent/target/agent-0.1.0-SNAPSHOT.jar" >"$WORK/agent.log" 2>&1 &
echo $! >"$WORK/agent.pid"
wait_for "agent online" "d['connectivity']=='ONLINE'"
echo "agente: $(agent_status)"
echo "com a central no ar, o agente NÃO usa a contingência: $(agent_validate "$TOKEN")"

step "QUEDA: derrubando a central (kill -9)"
stop_central
wait_for "agent offline" "d['connectivity']=='OFFLINE'"
echo "agente: $(agent_status)"
echo "1ª validação offline: $(agent_validate "$TOKEN")"
echo "2ª validação offline (uso único):  $(agent_validate "$TOKEN")"
echo "token adulterado:                  $(agent_validate "${TOKEN%?}x")"
echo "backlog no agente: $(agent_status | jget "d['backlog']")"

step "RETORNO: religando a central"
start_central
MANAGER=$(tok manager-sol); RESIDENT=$(tok resident-sol)
wait_for "backlog drained" "d['backlog']==0 and d['connectivity']=='ONLINE'"
echo "agente: $(agent_status)"
sleep 1
echo "uso consumido na central: $(curl -s -H "Authorization: Bearer $RESIDENT" $API/condominiums/$CONDO/me/invitations | jget "[(i['useCount'],i['maxUses']) for i in d['items'] if i['id']=='$INV_ID'][0]")"
echo "histórico do morador: $(curl -s -H "Authorization: Bearer $RESIDENT" $API/condominiums/$CONDO/me/access-history | jget "sorted([(a['result'],a['reason']) for a in d['items']])")"
echo "conflitos de sincronização: $(curl -s -H "Authorization: Bearer $MANAGER" $API/condominiums/$CONDO/sync-conflicts | jget "d['totalElements']")"
echo "agentes: $(curl -s -H "Authorization: Bearer $MANAGER" $API/condominiums/$CONDO/agents | jget "[(a['name'],a['online'],a['backlog']) for a in d]")"
step "OK"
