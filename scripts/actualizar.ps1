param([switch]$Https)
. (Join-Path $PSScriptRoot 'h8-common.ps1')
$lock = Lock-H8Maintenance
try {
    $compose = @('compose','--project-directory',$H8Root,'-f',(Join-Path $H8Root 'compose.yaml'))
    if ($Https) { $compose += @('-f',(Join-Path $H8Root 'compose.tls.yaml')) }
    Invoke-H8Docker ($compose + @('config','--quiet'))
    Invoke-H8Docker ($compose + @('build'))
    # No recrear MySQL al actualizar aplicaciones: conserva volumen y credenciales.
    Invoke-H8Docker ($compose + @('up','-d','--wait','--force-recreate','--no-deps','backend','frontend'))
} finally { $lock.Dispose() }
