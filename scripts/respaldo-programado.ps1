. (Join-Path $PSScriptRoot 'h8-common.ps1')
$config = Get-H8Configuration
$log = Join-Path $config.local 'ultima-ejecucion.log'
try {
    & (Join-Path $PSScriptRoot 'respaldar.ps1') *>&1 | Out-File -LiteralPath $log -Encoding utf8
    exit 0
} catch {
    'Respaldo no completado. Revisar Docker Desktop y ejecutar scripts/respaldar.ps1. No se rotan copias por esta falla.' | Out-File -LiteralPath $log -Encoding utf8 -Append
    exit 1
}
