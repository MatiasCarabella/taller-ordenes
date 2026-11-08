#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p .secrets
chmod 700 .secrets
for name in db_root_password db_app_password db_migration_password db_backup_password; do
  if [ ! -e ".secrets/$name" ]; then
    openssl rand -hex 32 | tr -d '\n' > ".secrets/$name"
  fi
  chmod 600 ".secrets/$name"
done

if [ ! -f .secrets/canales.properties ]; then printf '# Canales sin configurar\n' > .secrets/canales.properties; fi
printf '%s\n' 'Secretos locales preparados; los existentes se conservaron.'
