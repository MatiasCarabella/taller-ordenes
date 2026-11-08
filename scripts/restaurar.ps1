param(
    [Parameter(Mandatory=$true)][string]$Respaldo,
    [Parameter(Mandatory=$true)][string]$Destino,
    [int]$Puerto = 8088
)
. (Join-Path $PSScriptRoot 'h8-common.ps1')
$timer = [Diagnostics.Stopwatch]::StartNew()
$Destino = [IO.Path]::GetFullPath($Destino)
if (Test-Path -LiteralPath $Destino) { throw 'El destino de recuperación no debe existir.' }
Protect-H8Directory $Destino
$code = Join-Path $Destino 'codigo'
New-Item -ItemType Directory -Path $code | Out-Null
$project = 'taller-recuperacion-' + (Get-Date -Format 'yyyyMMddHHmmss')
$baseArgs = @('run','--rm','--read-only','--tmpfs','/tmp','--tmpfs','/work:rw,noexec,nosuid,size=1g','--mount',"type=bind,source=$H8Root/.secrets,target=/identity,readonly",'--mount',"type=bind,source=$([IO.Path]::GetFullPath($Respaldo)),target=/input,readonly")
Invoke-H8Docker ($baseArgs + @('--network','none','--mount',"type=bind,source=$code,target=/recovered",$H8Image,'code'))
& (Join-Path $code 'scripts/inicializar-secretos.ps1')
$previousPort = $env:HTTP_PORT
try {
    $env:HTTP_PORT = "$Puerto"
    $compose = @('compose','--project-directory',$code,'-f',(Join-Path $code 'compose.yaml'),'-p',$project)
    Invoke-H8Docker ($compose + @('up','-d','--wait','db'))
    $db = (& docker @compose ps -q db).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $db) { throw 'BDD de recuperación no disponible.' }
    Invoke-H8Docker ($baseArgs + @('--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',$H8Image,'restore','--db',$db))
    Invoke-H8Docker ($compose + @('up','-d','--build','--wait'))
    $timer.Stop()
    $evidence = @{ project=$project; code=$code; port=$Puerto; elapsedSeconds=[math]::Round($timer.Elapsed.TotalSeconds,3); restoredAt=(Get-Date).ToUniversalTime().ToString('o') }
    $evidence | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Destino 'recuperacion.json') -Encoding utf8
    $evidence | ConvertTo-Json -Compress
    Write-Output 'Entorno recuperado aislado. Los canales externos están deshabilitados; las contraseñas de las cuentas se conservan. No sustituye la instancia principal.'
} finally { $env:HTTP_PORT = $previousPort }
