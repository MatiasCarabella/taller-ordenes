$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$secretDirectory = Join-Path $projectRoot '.secrets'
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$directoryAcl = [Security.AccessControl.DirectorySecurity]::new()
$directoryAcl.SetAccessRuleProtection($true, $false)
$directoryAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    $currentSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
$directoryAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl',
    'ContainerInherit,ObjectInherit', 'None', 'Allow'))
if ('System.IO.FileSystemAclExtensions' -as [type]) {
    [System.IO.FileSystemAclExtensions]::SetAccessControl([System.IO.DirectoryInfo]::new($secretDirectory), $directoryAcl)
} else {
    [System.IO.DirectoryInfo]::new($secretDirectory).SetAccessControl($directoryAcl)
}
foreach ($secretName in @('db_root_password','db_app_password','db_migration_password','db_backup_password')) {
    $secretPath = Join-Path $secretDirectory $secretName
    if (-not (Test-Path -LiteralPath $secretPath)) {
        $secretBytes = New-Object byte[] 32
        $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $randomGenerator.GetBytes($secretBytes) } finally { $randomGenerator.Dispose() }
        $secretHex = ([BitConverter]::ToString($secretBytes)).Replace('-', '').ToLowerInvariant()
        [IO.File]::WriteAllText($secretPath, $secretHex, [Text.UTF8Encoding]::new($false))
    }
}
$channelSettings = Join-Path $secretDirectory 'canales.properties'
if (-not (Test-Path -LiteralPath $channelSettings)) { [IO.File]::WriteAllText($channelSettings, '# Canales sin configurar', [Text.UTF8Encoding]::new($false)) }
Write-Output 'Secretos locales preparados. Los existentes se conservaron; no se muestran valores.'
