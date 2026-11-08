param(
    [Parameter(Mandatory=$true)][string]$DestinoLocal,
    [string]$DestinoExterno,
    [switch]$Programar
)
. (Join-Path $PSScriptRoot 'h8-common.ps1')
if ((Get-TimeZone).Id -ne 'Argentina Standard Time') { throw 'Configurar zona horaria de Buenos Aires antes de programar a las 02:00.' }
& (Join-Path $PSScriptRoot 'inicializar-secretos.ps1')
Protect-H8Directory ([IO.Path]::GetFullPath($DestinoLocal))
if ($DestinoExterno) { Protect-H8Directory ([IO.Path]::GetFullPath($DestinoExterno)) }
Invoke-H8Docker @('build','-t',$H8Image,'-f',(Join-Path $H8Root 'infra/herramientas.Dockerfile'),$H8Root)
Invoke-H8Docker @('run','--rm','--network','none','--read-only','--tmpfs','/tmp','--mount',"type=bind,source=$H8Root/.secrets,target=/secrets",$H8Image,'keygen')
$configuration = @{ local=[IO.Path]::GetFullPath($DestinoLocal); external=$(if ($DestinoExterno) { [IO.Path]::GetFullPath($DestinoExterno) } else { $null }); timezone='America/Argentina/Buenos_Aires' }
[IO.File]::WriteAllText((Join-Path $H8Root '.secrets/respaldo.json'), ($configuration | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
if ($Programar) {
    $script = Join-Path $H8Root 'scripts/respaldo-programado.ps1'
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $script + '"') -WorkingDirectory $H8Root
    $trigger = New-ScheduledTaskTrigger -Daily -At '02:00'
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName 'TallerOrdenes-RespaldoDiario' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Write-Output 'Tarea diaria registrada a las 02:00 de Buenos Aires. Requiere sesión iniciada y Docker Desktop activo; recupera ejecuciones omitidas al estar disponible.'
}
Write-Output 'Respaldo configurado. Guardar la identidad .secrets/respaldo-age.key por separado en un lugar seguro; no incluirla en el depósito ni GitHub.'
