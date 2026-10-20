#!/usr/bin/env bash
# Backup lógico do PostgreSQL (formato custom do pg_dump) com checksum e, opcionalmente, criptografia GPG.
# Uso: scripts/backup.sh [diretório-de-destino]   (padrão: ./backups)
# Variáveis: COMPOSE_FILES ("-f docker-compose.yml"), PGSERVICE (postgres), PGDB (portaria), PGUSER (portaria),
#            BACKUP_GPG_RECIPIENT (se definida, o dump é cifrado para esse destinatário e o claro é apagado),
#            BACKUP_KEEP (quantidade de backups mantidos, padrão 14).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$(realpath -m "${1:-$ROOT/backups}")"
SERVICE="${PGSERVICE:-postgres}"; DB="${PGDB:-portaria}"; USER_="${PGUSER:-portaria}"
cd "$ROOT"   # caminhos relativos: o diretório do projeto pode conter espaços
COMPOSE_FILES="${COMPOSE_FILES:--f docker-compose.yml}"
mkdir -p "$DEST"; chmod 700 "$DEST"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$DEST/$DB-$STAMP.dump"
# shellcheck disable=SC2086
docker compose $COMPOSE_FILES exec -T "$SERVICE" pg_dump -U "$USER_" -d "$DB" -Fc --no-owner > "$FILE"
[ -s "$FILE" ] || { echo "backup vazio" >&2; rm -f "$FILE"; exit 1; }
chmod 600 "$FILE"
(cd "$DEST" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256")
if [ -n "${BACKUP_GPG_RECIPIENT:-}" ]; then
  gpg --batch --yes --encrypt --recipient "$BACKUP_GPG_RECIPIENT" --output "$FILE.gpg" "$FILE"
  rm -f "$FILE"; FILE="$FILE.gpg"
  (cd "$DEST" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256")
fi
# Retenção local dos backups
ls -1t "$DEST"/"$DB"-*.dump* 2>/dev/null | grep -v '\.sha256$' | tail -n +"$(( ${BACKUP_KEEP:-14} + 1 ))" | while read -r old; do rm -f "$old" "$old.sha256"; done
echo "$FILE"
