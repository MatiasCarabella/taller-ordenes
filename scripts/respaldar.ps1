. (Join-Path $PSScriptRoot 'h8-common.ps1')
$config = Get-H8Configuration
$db = Get-H8Container 'db'
$arguments = @('run','--rm','--read-only','--tmpfs','/tmp','--tmpfs','/work:rw,noexec,nosuid,size=1g','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock', '--mount',"type=bind,source=$H8Root,target=/source,readonly",'--mount',"type=bind,source=$H8Root/.secrets,target=/identity,readonly",'--mount',"type=bind,source=$($config.local),target=/backup")
if ($config.external) { $arguments += @('--mount',"type=bind,source=$($config.external),target=/external") }
$arguments += @($H8Image,'backup','--db',$db)
if ($config.external) { $arguments += '--external' }
$lock = Lock-H8Maintenance
try { Invoke-H8Docker $arguments } finally { $lock.Dispose() }
