#!/bin/sh
set -eu
# Generated secrets contain hexadecimal characters only; reject other input.
app_password="$(cat /run/secrets/db_app_password)"
migration_password="$(cat /run/secrets/db_migration_password)"
backup_password="$(cat /run/secrets/db_backup_password)"
for value in "$app_password" "$migration_password" "$backup_password"; do
  case "$value" in ''|*[!a-fA-F0-9]*) echo "Invalid database secret format" >&2; exit 1;; esac
done
MYSQL_PWD="$(cat /run/secrets/db_root_password)" mysql --protocol=socket -uroot <<SQL
CREATE USER 'taller_app'@'%' IDENTIFIED BY '$app_password';
CREATE USER 'taller_migrator'@'%' IDENTIFIED BY '$migration_password';
CREATE USER 'taller_backup'@'%' IDENTIFIED BY '$backup_password';
GRANT SELECT, INSERT, UPDATE ON taller.* TO 'taller_app'@'%';
GRANT ALL PRIVILEGES ON taller.* TO 'taller_migrator'@'%';
GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON taller.* TO 'taller_backup'@'%';
SQL
