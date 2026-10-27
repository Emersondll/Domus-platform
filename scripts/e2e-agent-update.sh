#!/usr/bin/env bash
# Prova ponta a ponta da atualização segura do agente com JARs reais: o agente 1.0.0 baixa a 1.1.0 assinada, se substitui, sai com 75 e o
# launcher sobe a 1.1.0; depois, um manifesto adulterado e um com outra chave são recusados e a versão não muda.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
WORK=$(mktemp -d)
cleanup() { [ -n "${HTTP_PID:-}" ] && kill "$HTTP_PID" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT
# roda o launcher por N segundos e para o agente com TERM (como o systemd), sem deixar java órfão
run_launcher_for() { agent/install/portaria-agent-run.sh > "$2" 2>&1 & local l=$!; sleep "$1"; pkill -TERM -P "$l" || true; wait "$l" || true; }
ok() { echo "  ok   $1"; }
die() { echo "  FAIL $1"; exit 1; }

echo "== build das versões 1.0.0 e 1.1.0 (cópias; o pom do repositório não muda)"
for v in 1.0.0 1.1.0; do
  mkdir -p "$WORK/src-$v"; cp -r agent/pom.xml agent/src "$WORK/src-$v/"
  sed -i "0,/<version>0.1.0-SNAPSHOT<\/version>/s//<version>$v<\/version>/" "$WORK/src-$v/pom.xml"
  (cd "$WORK/src-$v" && mvn -q -DskipTests package >/dev/null 2>&1) || die "build $v"
  cp "$WORK/src-$v/target/agent-$v.jar" "$WORK/agent-$v.jar" 2>/dev/null || cp "$(ls "$WORK/src-$v"/target/*.jar | grep -v original | head -1)" "$WORK/agent-$v.jar"
done
ok "dois jars gerados"

echo "== chaves e lançamento assinado da 1.1.0"
java -cp "$WORK/agent-1.1.0.jar" br.com.domus.agent.update.SignTool genkey "$WORK/keys" >/dev/null
PUB=$(cat "$WORK/keys/release-public.key")
java -cp "$WORK/agent-1.1.0.jar" br.com.domus.agent.update.SignTool sign "$WORK/agent-1.1.0.jar" 1.1.0 stable "$WORK/keys/release-private.key" "$WORK/release" "2020-01-01T00:00:00Z"
ok "manifest.json assinado"

PORT=$((20000 + RANDOM % 20000))
(cd "$WORK/release" && python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1) & HTTP_PID=$!
sleep 1

mkdir -p "$WORK/data/bin"; cp "$WORK/agent-1.0.0.jar" "$WORK/data/bin/agent.jar"
export PORTARIA_CENTRAL_URL=http://127.0.0.1:9 PORTARIA_AGENT_ID=$(python3 -c "import uuid;print(uuid.uuid4())") PORTARIA_AGENT_KEY=k \
  PORTARIA_POLICY_SECRET=p PORTARIA_STORE_KEY=e2e-agent-update-store-key-0123456789ab PORTARIA_LOCAL_KEY=l \
  PORTARIA_LOCAL_PORT=$((40000 + RANDOM % 20000)) PORTARIA_DATA_DIR="$WORK/data" PORTARIA_JAR_PATH="$WORK/data/bin/agent.jar" \
  PORTARIA_UPDATE_URL="http://127.0.0.1:$PORT/" PORTARIA_UPDATE_PUBLIC_KEY="$PUB" PORTARIA_UPDATE_FIRST_CHECK_SECONDS=3 \
  PORTARIA_STABLE_SECONDS=5

echo "== agente 1.0.0 encontra a 1.1.0, instala e pede reinício (código 75)"
set +e; timeout 40 agent/install/portaria-agent-run.sh > "$WORK/run1.log" 2>&1; code=$?; set -e
[ "$code" = 75 ] || { cat "$WORK/run1.log"; die "esperava saída 75, veio $code"; }
grep -q "running 1.0.0" "$WORK/run1.log" && ok "partiu na 1.0.0"
cmp -s "$WORK/data/bin/agent.jar" "$WORK/agent-1.1.0.jar" && ok "jar em disco agora é o da 1.1.0" || die "jar não foi trocado"
cmp -s "$WORK/data/bin/agent.jar.previous" "$WORK/agent-1.0.0.jar" && ok "1.0.0 guardada como .previous"
[ -f "$WORK/data/update-pending" ] && ok "marcador update-pending criado"

echo "== reinício (como o systemd faria): sobe a 1.1.0 e a atualização é confirmada"
# o systemd para o serviço enviando TERM ao processo do agente (não ao launcher): o launcher então avalia a estabilidade.
# (Com `timeout` sobre o launcher o próprio script morria antes de avaliar e deixava o java órfão.)
run_launcher_for 8 "$WORK/run2.log"
grep -q "running 1.1.0" "$WORK/run2.log" && ok "agora executando 1.1.0" || { cat "$WORK/run2.log"; die "não subiu a 1.1.0"; }
[ ! -f "$WORK/data/update-pending" ] && ok "atualização confirmada (marcador removido)" || die "marcador continua"

echo "== manifesto adulterado e manifesto de outra chave: recusados, versão não muda"
before=$(sha256sum "$WORK/data/bin/agent.jar" | cut -d' ' -f1)
sed -i 's/"version":"1.1.0"/"version":"9.9.9"/' "$WORK/release/manifest.json"
run_launcher_for 12 "$WORK/run3.log"
grep -q "update manifest rejected: invalid signature" "$WORK/run3.log" && ok "manifesto adulterado recusado (assinatura inválida)" || { cat "$WORK/run3.log"; die "adulterado não foi recusado"; }
java -cp "$WORK/agent-1.1.0.jar" br.com.domus.agent.update.SignTool genkey "$WORK/evil" >/dev/null
cp "$WORK/agent-1.1.0.jar" "$WORK/evil-9.9.9.jar"
java -cp "$WORK/agent-1.1.0.jar" br.com.domus.agent.update.SignTool sign "$WORK/evil-9.9.9.jar" 9.9.9 stable "$WORK/evil/release-private.key" "$WORK/release" "2020-01-01T00:00:00Z" >/dev/null
run_launcher_for 12 "$WORK/run4.log"
grep -q "update manifest rejected: invalid signature" "$WORK/run4.log" && ok "manifesto assinado por outra chave recusado" || { cat "$WORK/run4.log"; die "outra chave não foi recusada"; }
[ "$(sha256sum "$WORK/data/bin/agent.jar" | cut -d' ' -f1)" = "$before" ] && ok "o jar em disco não mudou"
echo "== OK"
