#!/usr/bin/env bash
# Exercício de restauração: backup do banco atual -> restauração em banco temporário -> verificações -> limpeza.
# Falha (exit 1) se qualquer verificação divergir. Rode periodicamente (ex.: mensal) e após mudar o esquema.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE="${PGSERVICE:-postgres}"; DB="${PGDB:-portaria}"; USER_="${PGUSER:-portaria}"
cd "$ROOT"   # caminhos relativos: o diretório do projeto pode conter espaços
COMPOSE_FILES="${COMPOSE_FILES:--f docker-compose.yml}"
TMP_DB="restore_drill_$(date +%s)"
WORK="$(mktemp -d)"
psql_() { docker compose $COMPOSE_FILES exec -T "$SERVICE" psql -U "$USER_" -d "$1" -At -v ON_ERROR_STOP=1 -c "$2"; }
cleanup() { docker compose $COMPOSE_FILES exec -T "$SERVICE" psql -U "$USER_" -d postgres -c "DROP DATABASE IF EXISTS $TMP_DB" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

START=$(date +%s)
DUMP="$("$ROOT/scripts/backup.sh" "$WORK")"
echo "backup: $(basename "$DUMP") ($(stat -c %s "$DUMP") bytes)"
"$ROOT/scripts/restore.sh" "$DUMP" "$TMP_DB" >/dev/null
echo "restauração concluída em $(( $(date +%s) - START ))s"

FAIL=0
for t in operator_company condominium app_user membership unit resident access_credential access_attempt access_command device agent agent_event sync_conflict service_call audit_log flyway_schema_history; do
  a=$(psql_ "$DB" "select count(*) from $t"); b=$(psql_ "$TMP_DB" "select count(*) from $t")
  if [ "$a" = "$b" ]; then printf '  ok   %-26s %s linhas\n' "$t" "$a"; else printf '  FAIL %-26s origem=%s restaurado=%s\n' "$t" "$a" "$b"; FAIL=1; fi
done
# Integridade estrutural: constraints, índices e o trigger que torna a auditoria append-only
for q in "select count(*) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='public'" \
         "select count(*) from pg_indexes where schemaname='public'" \
         "select count(*) from pg_trigger where not tgisinternal"; do
  a=$(psql_ "$DB" "$q"); b=$(psql_ "$TMP_DB" "$q")
  if [ "$a" = "$b" ]; then echo "  ok   estrutura ($a)"; else echo "  FAIL estrutura origem=$a restaurado=$b"; FAIL=1; fi
done
out=$(psql_ "$TMP_DB" "delete from audit_log" 2>&1 || true)   # captura antes: grep -q + pipefail daria falso negativo
if grep -q "append-only" <<<"$out"; then echo "  ok   audit_log continua append-only após a restauração"; else echo "  FAIL audit_log aceitou DELETE"; FAIL=1; fi
if [ "$FAIL" = 0 ]; then echo "RESTORE DRILL: PASS"; else echo "RESTORE DRILL: FAIL"; exit 1; fi
