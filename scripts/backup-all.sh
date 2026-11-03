#!/usr/bin/env bash
# Backup de TODOS os bancos que precisam voltar juntos: `portaria` (dados da operação) e `keycloak` (contas de login,
# MFA e vínculo `sub` da equipe e dos moradores). Restaurar só o primeiro deixa todo mundo sem conseguir entrar.
# Uso: scripts/backup-all.sh [diretório-de-destino]
# Variáveis: as de backup.sh (COMPOSE_FILES, PGSERVICE, PGUSER, BACKUP_GPG_RECIPIENT, BACKUP_KEEP) e
#            BACKUP_DBS (lista separada por espaço; padrão "portaria keycloak").
# Em produção:  COMPOSE_FILES="--env-file deploy/.env.prod -f deploy/docker-compose.prod.yml" PGSERVICE=db scripts/backup-all.sh /var/backups/domus
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for db in ${BACKUP_DBS:-portaria keycloak}; do
  PGDB="$db" "$ROOT/scripts/backup.sh" "$@"
done
