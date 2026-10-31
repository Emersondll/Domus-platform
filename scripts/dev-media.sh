#!/usr/bin/env bash
# Servidor de mídia de DESENVOLVIMENTO com fontes RTSP falsas (padrões de teste do ffmpeg). Não é para produção:
# aqui as fontes de teste são lidas sem token. Uso: scripts/dev-media.sh up | down | status
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="${DOMUS_RUN_DIR:-/tmp/domus-run}"
NAME=domus-media
IMAGE=bluenviron/mediamtx:1.15.1
PUB_SECRET="${MEDIA_PUBLISH_SECRET:-dev-only-media-publish-secret}"
# Marca no $0 dos laços: permite achar e encerrar laços órfãos de execuções anteriores (o .pid se perde se o `up` roda duas vezes).
LOOP_TAG=domus-dev-media-source
SOURCES=(fake-src:testsrc src-portaria:testsrc2 src-garagem:smptebars src-social:rgbtestsrc src-hall:mandelbrot)

# Cada fonte roda num laço que reabre o ffmpeg se ele cair (ex.: o servidor de mídia reiniciou); o pid guardado é o do laço.
start_source() {
  local path="${1%%:*}" pattern="${1##*:}"
  nohup bash -c 'while true; do
      ffmpeg -loglevel error -re -f lavfi -i "$1=size=640x360:rate=15" -c:v libx264 -preset ultrafast \
        -tune zerolatency -g 15 -pix_fmt yuv420p -f rtsp -rtsp_transport tcp "rtsp://pub:$2@127.0.0.1:8554/$3"
      sleep 2
    done' "$LOOP_TAG" "$pattern" "$PUB_SECRET" "$path" > "$RUN/ff-$path.log" 2>&1 &
  echo $! > "$RUN/ff-$path.pid"
}

up() {
  mkdir -p "$RUN"
  kill_orphans   # garante uma só publicação por caminho (laços duplicados derrubam um ao outro: "closing existing publisher")
  # config de produção + exceção de leitura (sem token) só para as fontes falsas
  extra="  # SOMENTE desenvolvimento: as fontes falsas de câmera são lidas sem token."
  for s in "${SOURCES[@]}"; do extra+=$'\n'"  - action: read"$'\n'"    path: ${s%%:*}"; done
  EXTRA="$extra" awk '{print} /^  - action: pprof/ {print ENVIRON["EXTRA"]}' "$ROOT/deploy/media/mediamtx.yml" > "$RUN/mtx-test.yml"
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d --name "$NAME" --network host -v "$RUN/mtx-test.yml:/mediamtx.yml:ro" "$IMAGE" >/dev/null
  for _ in $(seq 1 30); do curl -fs -o /dev/null http://127.0.0.1:9997/v3/config/global/get 2>/dev/null && break; sleep 1; done
  for s in "${SOURCES[@]}"; do start_source "$s"; done
  echo "Servidor de mídia no ar. Fontes: $(printf '%s ' "${SOURCES[@]%%:*}")"
}

# Encerra TODO laço marcado e os ffmpeg que publicam no servidor de mídia local, mesmo os que não têm .pid.
kill_orphans() {
  pkill -TERM -f "[d]omus-dev-media-source" 2>/dev/null || true
  pkill -TERM -f "[f]fmpeg .*rtsp://pub:.*@127.0.0.1:8554/" 2>/dev/null || true
}

down() {
  for f in "$RUN"/ff-*.pid; do
    [ -f "$f" ] || continue
    pkill -TERM -P "$(cat "$f")" 2>/dev/null || true   # o ffmpeg filho
    kill "$(cat "$f")" 2>/dev/null || true
    rm -f "$f"
  done
  kill_orphans
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "Servidor de mídia parado."
}

status() {
  docker ps --filter "name=$NAME" --format '{{.Names}} {{.Status}}'
  for f in "$RUN"/ff-*.pid; do [ -f "$f" ] && { kill -0 "$(cat "$f")" 2>/dev/null && echo "ok   $(basename "$f" .pid)" || echo "MORTO $(basename "$f" .pid)"; }; done
  echo "laços de fonte ativos: $(pgrep -fc '[d]omus-dev-media-source' || true) (esperado: ${#SOURCES[@]})"
  true
}

# Religa só as fontes cujo laço morreu (o servidor de mídia precisa estar no ar).
repair() {
  for s in "${SOURCES[@]}"; do
    local path="${s%%:*}" pidfile="$RUN/ff-${s%%:*}.pid"
    if [ ! -f "$pidfile" ] || ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then start_source "$s"; echo "religada: $path"; fi
  done
}

case "${1:-}" in up) up ;; down) down ;; status) status ;; repair) repair ;; *) echo "uso: $0 up|down|status|repair" >&2; exit 2 ;; esac
