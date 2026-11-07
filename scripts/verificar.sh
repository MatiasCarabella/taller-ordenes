#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose config --quiet
docker compose build
docker build --target build --tag taller-ordenes-pruebas --file infra/backend.Dockerfile .
# Desktop resolves host.docker.internal; on Linux --add-host supplies the same name.
docker run --rm \
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
  --mount type=volume,source=taller-ordenes_maven_pruebas,target=/root/.m2 \
  --add-host host.docker.internal:host-gateway \
  --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  taller-ordenes-pruebas ./mvnw -B test
