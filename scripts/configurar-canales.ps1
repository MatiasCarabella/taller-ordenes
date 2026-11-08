$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'inicializar-secretos.ps1')
function Ask([string]$Prompt,[string]$Default='') {
    $label=$Prompt
    if($Default){$label+=" [$Default]"}
    $answer=Read-Host $label
    if([string]::IsNullOrWhiteSpace($answer)){return $Default}
    return $answer.Trim()
}
function Secret([string]$Prompt){
    $secure=Read-Host $Prompt -AsSecureString
    $pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try{return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)}
    finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)}
}
function Property([string]$Value){
    return $Value.Replace('\','\\').Replace([string][char]13,'\r').Replace([string][char]10,'\n').Replace([string][char]9,'\t').Replace('=','\=').Replace(':','\:')
}
Write-Host 'Configuración local de canales. Las claves no se muestran ni se envían por este script.'
Write-Host 'Para omitir un canal, dejá su usuario o token vacío. Una configuración existente se reemplazará.'
$portal=Ask 'URL del portal (HTTPS en remoto)' 'http://localhost:8080'
$smtpHost=Ask 'Servidor SMTP' 'smtp-relay.brevo.com'
$smtpPort=Ask 'Puerto SMTP (587 STARTTLS o 465 TLS)' '587'
$smtpUser=Ask 'Usuario SMTP'
$smtpFrom=Ask 'Correo remitente verificado'
$smtpKey=if($smtpUser){Secret 'Clave SMTP (oculta; no es la clave de acceso a Brevo)'}else{''}
$botUsername=(Ask 'Usuario del bot de Telegram (sin @)').TrimStart('@')
$botToken=if($botUsername){Secret 'Token de BotFather (oculto)'}else{''}
$uri=$null
if(-not [Uri]::TryCreate($portal,[UriKind]::Absolute,[ref]$uri) -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
    ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.IsLoopback))){throw 'Usá HTTPS o HTTP únicamente en localhost.'}
if($smtpPort -notin @('587','465','2525')){throw 'Puerto SMTP inválido.'}
if($smtpUser -and ($smtpFrom -notmatch '^[^\s<>]+@[^\s<>]+$' -or -not $smtpKey)){throw 'Falta remitente o clave SMTP.'}
if($botUsername -and ($botUsername -notmatch '^[A-Za-z0-9_]{5,32}$' -or $botToken -notmatch '^[0-9]+:[A-Za-z0-9_-]+$')){throw 'Revisá el usuario y token del bot.'}
$settings=[ordered]@{
 'taller.canales.habilitados'='true'
 'taller.canales.portal'=$portal.TrimEnd('/')
 'taller.canales.smtp.host'=$smtpHost
 'taller.canales.smtp.puerto'=$smtpPort
 'taller.canales.smtp.usuario'=$smtpUser
 'taller.canales.smtp.clave'=$smtpKey
 'taller.canales.smtp.remitente'=$smtpFrom
 'taller.canales.telegram.usuario'=$botUsername
 'taller.canales.telegram.token'=$botToken
}
$lines=foreach($entry in $settings.GetEnumerator()){$entry.Key+'='+(Property ([string]$entry.Value))}
$target=Join-Path $projectRoot '.secrets/canales.properties'
[IO.File]::WriteAllText($target,($lines -join [char]10)+[char]10,[Text.UTF8Encoding]::new($false))
$smtpKey=$null;$botToken=$null;$settings=$null;$lines=$null
Write-Host 'Guardado en .secrets/canales.properties, protegido y excluido de Git.'
Write-Host 'Cuando esté instalada la versión H7, aplicá con: docker compose up -d --wait --force-recreate --no-deps backend frontend'
