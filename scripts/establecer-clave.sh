#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --wait db
docker compose run --rm -it --no-deps backend --spring.main.web-application-type=none --taller.cuentas=clave
