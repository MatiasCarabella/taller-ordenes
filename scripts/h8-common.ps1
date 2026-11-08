$ErrorActionPreference = 'Stop'
$H8Root = Split-Path -Parent $PSScriptRoot
$H8Image = 'taller-ordenes-herramientas:h8'
function Invoke-H8Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Falló la operación Docker de H8; no se considera una copia válida.' }
}
function Protect-H8Directory([string]$Path) {
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    if ('System.IO.FileSystemAclExtensions' -as [type]) { [System.IO.FileSystemAclExtensions]::SetAccessControl([IO.DirectoryInfo]::new($Path), $acl) }
    else { [IO.DirectoryInfo]::new($Path).SetAccessControl($acl) }
}
function Get-H8Configuration {
    $path = Join-Path $H8Root '.secrets/respaldo.json'
    if (-not (Test-Path -LiteralPath $path)) { throw 'Ejecutar primero configurar-respaldos.ps1.' }
    Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}
function Lock-H8Maintenance {
    $path = Join-Path $H8Root '.secrets/operacion.lock'
    try { return [IO.File]::Open($path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
    catch { throw 'Otra operación de respaldo o actualización está activa. Reintentar al finalizar.' }
}
function Get-H8Container([string]$Service) {
    $id = & docker compose --project-directory $H8Root -f (Join-Path $H8Root 'compose.yaml') ps -q $Service
    if ($LASTEXITCODE -ne 0 -or -not $id) { throw "No está activo el servicio $Service" }
    $directory = & docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' $id.Trim()
    if ($LASTEXITCODE -ne 0 -or ([IO.Path]::GetFullPath($directory).Replace('\','/').TrimEnd('/') -ne [IO.Path]::GetFullPath($H8Root).Replace('\','/').TrimEnd('/'))) { throw 'El contenedor pertenece a otro directorio de proyecto.' }
    return $id.Trim()
}
