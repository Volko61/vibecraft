<#
.SYNOPSIS
  Puts the main Paper server behind the Velocity proxy (modern forwarding).

.DESCRIPTION
  Edits <ServerDir>\server.properties and <ServerDir>\config\paper-global.yml:
    server.properties : server-ip=127.0.0.1 (only Velocity, on the same PC, can reach it),
                        server-port=<MainPort> (only if -MainPort is given; default: unchanged),
                        online-mode=false, network-compression-threshold=-1
    paper-global.yml  : proxies.velocity.enabled=true, online-mode=<OnlineMode>, secret=<forwarding.secret>
  The previous values are saved once in <ServerDir>\vibeproxy-backup.json so that
  disable-proxy-mode.ps1 restores them exactly. Takes effect at the next server start.
  Afterwards players MUST connect through the proxy (direct connections are refused by Paper).

.EXAMPLE
  proxy\enable-proxy-mode.ps1                      # main stays on 25565 (localhost), players use Velocity :25577
  proxy\enable-proxy-mode.ps1 -MainPort 25566      # frees 25565 for Velocity (start-proxy.ps1 -ProxyPort 25565)
  proxy\enable-proxy-mode.ps1 -ServerDir D:\copie\server -OnlineMode
#>
param(
    [string]$ServerDir,
    # New port of the main server (0 = keep the current server-port)
    [int]$MainPort = 0,
    # Must match start-proxy.ps1 -OnlineMode (Mojang authentication done by the proxy)
    [switch]$OnlineMode,
    # Keep the main server reachable from other machines (not recommended: bypasses the proxy)
    [switch]$ListenOnAllInterfaces
)
. (Join-Path $PSScriptRoot 'lib\common.ps1')
if (-not $ServerDir) { $ServerDir = Join-Path $script:RootDir 'server' }
$ServerDir = (Resolve-Path $ServerDir).Path
$props = Join-Path $ServerDir 'server.properties'
$paperGlobal = Join-Path $ServerDir 'config\paper-global.yml'
if (-not (Test-Path $props)) { throw "server.properties introuvable dans $ServerDir" }
if (-not (Test-Path $paperGlobal)) { throw "config\paper-global.yml introuvable dans $ServerDir (demarre le serveur une fois)" }

$backupFile = Join-Path $ServerDir 'vibeproxy-backup.json'
$propKeys = 'server-port', 'server-ip', 'online-mode', 'network-compression-threshold'
if (Test-Path $backupFile) {
    Write-Info "Sauvegarde existante conservee : $backupFile (deja en mode proxy ?)"
} else {
    $vel = Get-PaperVelocity $paperGlobal
    $backup = [ordered]@{ createdAt = (Get-Date).ToString('o'); properties = [ordered]@{}; velocity = [ordered]@{} }
    foreach ($k in $propKeys) { $backup.properties[$k] = Get-PropValue $props $k }
    foreach ($k in 'enabled', 'online-mode', 'secret') { $backup.velocity[$k] = $vel[$k] }
    Write-TextFile $backupFile ($backup | ConvertTo-Json -Depth 4)
    Write-Info "Valeurs d'origine sauvegardees dans $backupFile"
}

$secret = Get-ForwardingSecret
$port = Get-PropValue $props 'server-port'
if ($port -and (Test-PortOpen ([int]$port))) {
    Write-Warn2 "Un serveur semble tourner sur le port $port : les changements s'appliqueront au prochain redemarrage."
}
if ($MainPort) { Set-PropValue $props 'server-port' $MainPort }
$MainPort = Get-PropValue $props 'server-port'
if (-not $MainPort) { $MainPort = 25565 }
if ($ListenOnAllInterfaces) { Set-PropValue $props 'server-ip' '' } else { Set-PropValue $props 'server-ip' '127.0.0.1' }
Set-PropValue $props 'online-mode' 'false'
Set-PropValue $props 'network-compression-threshold' '-1'
Set-PaperVelocity $paperGlobal $true ([bool]$OnlineMode) $secret

Write-Ok "Mode proxy ACTIVE pour $ServerDir"
Write-Host "   serveur principal : 127.0.0.1:$MainPort (joignable uniquement via Velocity ; limbo : 127.0.0.1:25567)"
Write-Host "   online-mode proxy : $([bool]$OnlineMode)   (doit correspondre a start-proxy.ps1 -OnlineMode)"
if ([int]$MainPort -eq 25565) {
    Write-Host '   Ensuite : proxy\start-proxy.ps1 (joueurs sur le port 25577) puis proxy\run-main-forever.ps1'
} else {
    Write-Host '   Ensuite : proxy\start-proxy.ps1 -ProxyPort 25565 (ou 25577 par defaut) puis proxy\run-main-forever.ps1'
}
