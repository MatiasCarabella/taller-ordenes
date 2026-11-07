$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose up -d --wait db
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar la BDD' }
    docker compose run --rm -it --no-deps backend --spring.main.web-application-type=none --taller.cuentas=clave
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo establecer la contraseña' }
} finally { Pop-Location }
