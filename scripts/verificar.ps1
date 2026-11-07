$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Compose inválido' }
    docker compose build
    if ($LASTEXITCODE -ne 0) { throw 'Falló la construcción de las aplicaciones' }
    docker build --target build --tag taller-ordenes-pruebas --file infra/backend.Dockerfile .
    if ($LASTEXITCODE -ne 0) { throw 'Falló la construcción del entorno de pruebas' }
    docker run --rm --mount 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock' --mount 'type=volume,source=taller-ordenes_maven_pruebas,target=/root/.m2' --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal taller-ordenes-pruebas ./mvnw -B test
    if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas del backend' }
} finally { Pop-Location }
