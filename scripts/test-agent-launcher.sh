#!/usr/bin/env bash
# Testa o launcher do agente (agent/install/portaria-agent-run.sh): confirmação e rollback de atualização, com um "java" falso.
set -u
cd "$(dirname "$0")/.."
LAUNCHER=agent/install/portaria-agent-run.sh
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (esperado '$3', obtido '$2')"; fail=1; fi; }

# "java" falso: sai com o código de $FAKE_EXIT depois de registrar qual jar rodou
cat > "$WORK/fakejava" <<'JAVA'
#!/usr/bin/env bash
echo "$2" >> "$FAKE_LOG"
exit "${FAKE_EXIT:-0}"
JAVA
chmod +x "$WORK/fakejava"

setup() { rm -rf "$WORK/data"; mkdir -p "$WORK/data/bin"; echo NOVO > "$WORK/data/bin/agent.jar"; echo VELHO > "$WORK/data/bin/agent.jar.previous"; : > "$WORK/log"; }
run() { PORTARIA_DATA_DIR="$WORK/data" JAVA_BIN="$WORK/fakejava" FAKE_LOG="$WORK/log" FAKE_EXIT="${1:-0}" PORTARIA_STABLE_SECONDS="${2:-60}" "$LAUNCHER" 2>/dev/null; }

echo "== sem atualização pendente: só repassa o código de saída"
setup; run 75; check "código 75 repassado (reinício pedido)" "$?" "75"
check "nenhum marcador criado" "$(ls "$WORK/data" | grep -c update)" "0"

echo "== versão nova que se mantém de pé é confirmada"
setup; echo "1.1.0|agora" > "$WORK/data/update-pending"; run 0 0
check "marcador removido" "$([ -f "$WORK/data/update-pending" ] && echo sim || echo nao)" "nao"
check "jar novo mantido" "$(cat "$WORK/data/bin/agent.jar")" "NOVO"

echo "== versão nova que cai logo: 2 falhas não revertem, a 3ª reverte"
setup; echo "1.1.0|agora" > "$WORK/data/update-pending"
run 1 9999; check "após 1 falha o jar novo continua" "$(cat "$WORK/data/bin/agent.jar")" "NOVO"
run 1 9999; check "após 2 falhas o jar novo continua" "$(cat "$WORK/data/bin/agent.jar")" "NOVO"
run 1 9999; check "na 3ª falha volta ao jar anterior" "$(cat "$WORK/data/bin/agent.jar")" "VELHO"
check "marcador e contador limpos" "$(ls "$WORK/data" | grep -c update)" "0"
check "o .previous foi consumido" "$([ -f "$WORK/data/bin/agent.jar.previous" ] && echo sim || echo nao)" "nao"

echo "== sem jar anterior não há o que restaurar (não quebra)"
setup; rm "$WORK/data/bin/agent.jar.previous"; echo "1.1.0|agora" > "$WORK/data/update-pending"
run 1 9999; run 1 9999; run 1 9999
check "jar novo continua (sem previous)" "$(cat "$WORK/data/bin/agent.jar")" "NOVO"

[ "$fail" = 0 ] && echo "LAUNCHER: OK" || { echo "LAUNCHER: FALHOU"; exit 1; }
