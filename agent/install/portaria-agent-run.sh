#!/usr/bin/env bash
# Launcher do agente (rodado pelo systemd; instale em /opt/portaria-agent/, dono root, NÃO gravável pelo usuário do agente).
# Papel: se uma atualização recém-instalada não se mantiver de pé, volta à versão anterior.
#   - o agente grava $DATA/update-pending ao instalar uma versão nova e sai com código 75 (reinício);
#   - se a versão nova encerrar antes de PORTARIA_STABLE_SECONDS (60) por 3 vezes seguidas, restaura <jar>.previous;
#   - se ela ficar de pé além desse tempo, a atualização é considerada confirmada e o marcador é removido.
set -u
DATA="${PORTARIA_DATA_DIR:-/var/lib/portaria-agent}"
JAR="${PORTARIA_JAR_PATH:-$DATA/bin/agent.jar}"
JAVA="${JAVA_BIN:-java}"
STABLE="${PORTARIA_STABLE_SECONDS:-60}"
PENDING="$DATA/update-pending"
FAILS="$DATA/update-fails"

start=$(date +%s)
"$JAVA" -jar "$JAR"
code=$?
elapsed=$(( $(date +%s) - start ))

if [ -f "$PENDING" ]; then
  if [ "$elapsed" -ge "$STABLE" ]; then
    rm -f "$PENDING" "$FAILS"
    echo "portaria-agent: update confirmed (ran ${elapsed}s)" >&2
  else
    n=$(( $(cat "$FAILS" 2>/dev/null || echo 0) + 1 ))
    echo "$n" > "$FAILS"
    echo "portaria-agent: new version exited after ${elapsed}s (failure $n/3)" >&2
    if [ "$n" -ge 3 ] && [ -f "$JAR.previous" ]; then
      mv -f "$JAR.previous" "$JAR"
      rm -f "$PENDING" "$FAILS"
      echo "portaria-agent: ROLLED BACK to the previous version" >&2
    fi
  fi
fi
exit "$code"
