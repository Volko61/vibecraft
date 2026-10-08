<#
.SYNOPSIS
  Takes the main Paper server out of proxy mode (players connect directly again).

.DESCRIPTION
  Restores the values saved by enable-proxy-mode.ps1 in <ServerDir>\vibeproxy-backup.json
  (server-port, server-ip, online-mode, network-compression-threshold, proxies.velocity.*).
  Without backup: server-port=25565, server-ip empty, proxies.velocity.enabled=false.
  Takes effect at the next server start. Stop the proxy first (proxy\stop-proxy.ps1), above all if it
  listens on the port the server gets back (25565).
#>
param([string]$ServerDir)
. (Join-Path $PSScriptRoot 'lib\common.ps1')
if (-not $ServerDir) { $ServerDir = Join-Path $script:RootDir 'server' }
$ServerDir = (Resolve-Path $ServerDir).Path
$props = Join-Path $ServerDir 'server.properties'
$paperGlobal = Join-Path $ServerDir 'config\paper-global.yml'
$backupFile = Join-Path $ServerDir 'vibeproxy-backup.json'
if (-not (Test-Path $props)) { throw "server.properties introuvable dans $ServerDir" }

if (Test-Path $backupFile) {
    $backup = (Read-TextFile $backupFile) | ConvertFrom-Json
    foreach ($p in $backup.properties.PSObject.Properties) {
        # $null = the key did not exist before -> remove it
        Set-PropValue $props $p.Name $p.Value
    }
    $v = $backup.velocity
    $enabled = ($v.enabled -eq 'true')
    $online = if ($null -eq $v.'online-mode') { $true } else { $v.'online-mode' -eq 'true' }
    $secret = if ($null -eq $v.secret) { '' } else { [string]$v.secret }
    if (Test-Path $paperGlobal) { Set-PaperVelocity $paperGlobal $enabled $online $secret }
    Remove-Item $backupFile -Force
    Write-Ok "Valeurs d'origine restaurees depuis la sauvegarde"
} else {
    Write-Warn2 'Pas de sauvegarde : valeurs par defaut (port 25565, velocity desactive)'
    Set-PropValue $props 'server-port' '25565'
    Set-PropValue $props 'server-ip' ''
    if (Test-Path $paperGlobal) {
        $cur = Get-PaperVelocity $paperGlobal
        $online = if ($null -eq $cur['online-mode']) { $true } else { $cur['online-mode'] -eq 'true' }
        Set-PaperVelocity $paperGlobal $false $online ''
    }
}
Write-Ok "Mode proxy DESACTIVE pour $ServerDir (port $(Get-PropValue $props 'server-port'))"
if (Get-PidFileProcess 'velocity') { Write-Warn2 'Velocity tourne encore : proxy\stop-proxy.ps1 pour l''arreter (et liberer son port).' }
