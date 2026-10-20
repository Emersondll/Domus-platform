#!/usr/bin/env bash
# Restaura um dump em um banco NOVO (nunca sobrescreve o banco em uso).
# Uso: scripts/restore.sh <arquivo.dump> <nome-do-banco-destino>
# Para restaurar em produção: restaure em um banco novo, valide e só então aponte DB_URL para ele (ver docs/operations.md).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUMP="$(realpath "${1:?informe o arquivo .dump}")"; TARGET="${2:?informe o banco de destino}"
SERVICE="${PGSERVICE:-postgres}"; USER_="${PGUSER:-portaria}"
cd "$ROOT"   # caminhos relativos: o diretório do projeto pode conter espaços
COMPOSE_FILES="${COMPOSE_FILES:--f docker-compose.yml}"
[ -f "$DUMP.sha256" ] && (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256")
case "$TARGET" in *[!a-zA-Z0-9_]*) echo "nome de banco inválido" >&2; exit 1;; esac
# shellcheck disable=SC2086
docker compose $COMPOSE_FILES exec -T "$SERVICE" psql -U "$USER_" -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $TARGET"
# shellcheck disable=SC2086
docker compose $COMPOSE_FILES exec -T "$SERVICE" pg_restore -U "$USER_" -d "$TARGET" --no-owner --exit-on-error < "$DUMP"
echo "restaurado em $TARGET"
