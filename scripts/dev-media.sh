#!/usr/bin/env bash
# Servidor de mídia de DESENVOLVIMENTO com fontes RTSP falsas (padrões de teste do ffmpeg). Não é para produção:
# aqui as fontes de teste são lidas sem token. Uso: scripts/dev-media.sh up | down | status
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN="${DOMUS_RUN_DIR:-/tmp/domus-run}"
NAME=domus-media
IMAGE=bluenviron/mediamtx:1.15.1
PUB_SECRET="${MEDIA_PUBLISH_SECRET:-dev-only-media-publish-secret}"
SOURCES=(fake-src:testsrc src-portaria:testsrc2 src-garagem:smptebars src-social:rgbtestsrc src-hall:mandelbrot)

up() {
  mkdir -p "$RUN"
  # config de produção + exceção de leitura (sem token) só para as fontes falsas
  extra="  # SOMENTE desenvolvimento: as fontes falsas de câmera são lidas sem token."
  for s in "${SOURCES[@]}"; do extra+=$'\n'"  - action: read"$'\n'"    path: ${s%%:*}"; done
  EXTRA="$extra" awk '{print} /^  - action: pprof/ {print ENVIRON["EXTRA"]}' "$ROOT/deploy/media/mediamtx.yml" > "$RUN/mtx-test.yml"
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker run -d --name "$NAME" --network host -v "$RUN/mtx-test.yml:/mediamtx.yml:ro" "$IMAGE" >/dev/null
  for _ in $(seq 1 30); do curl -fs -o /dev/null http://127.0.0.1:9997/v3/config/global/get 2>/dev/null && break; sleep 1; done
  for s in "${SOURCES[@]}"; do
    path="${s%%:*}"; pattern="${s##*:}"
    nohup ffmpeg -loglevel error -re -f lavfi -i "${pattern}=size=640x360:rate=15" -c:v libx264 -preset ultrafast \
      -tune zerolatency -g 15 -pix_fmt yuv420p -f rtsp -rtsp_transport tcp \
      "rtsp://pub:${PUB_SECRET}@127.0.0.1:8554/${path}" > "$RUN/ff-$path.log" 2>&1 &
    echo $! > "$RUN/ff-$path.pid"
  done
  echo "Servidor de mídia no ar. Fontes: $(printf '%s ' "${SOURCES[@]%%:*}")"
}

down() {
  for f in "$RUN"/ff-*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; done
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "Servidor de mídia parado."
}

status() {
  docker ps --filter "name=$NAME" --format '{{.Names}} {{.Status}}'
  for f in "$RUN"/ff-*.pid; do [ -f "$f" ] && { kill -0 "$(cat "$f")" 2>/dev/null && echo "ok   $(basename "$f" .pid)" || echo "MORTO $(basename "$f" .pid)"; }; done
  true
}

case "${1:-}" in up) up ;; down) down ;; status) status ;; *) echo "uso: $0 up|down|status" >&2; exit 2 ;; esac
