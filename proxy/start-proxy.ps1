<#
.SYNOPSIS
  Starts the VibeCraft safety net: the limbo backend (background) + the Velocity proxy.

.DESCRIPTION
  By default Velocity runs in THIS window (type "vibeproxy status", "end"...). When Velocity exits
  (command "end" or Ctrl+C), the limbo is stopped too.
  With -Background both run hidden; logs in proxy\run\, stop them with proxy\stop-proxy.ps1.
  The main server is NOT started here: use proxy\run-main-forever.ps1 in another window.

  Ports (defaults): players -> Velocity 0.0.0.0:25577; Velocity -> main server at the address read
  from <ServerDir>\server.properties (server-ip / server-port, i.e. 127.0.0.1:25565 after
  enable-proxy-mode.ps1); limbo 127.0.0.1:25567 (RCON 25568).

.EXAMPLE
  proxy\start-proxy.ps1                       # players connect to <this PC>:25577
  proxy\start-proxy.ps1 -ProxyPort 25565      # players keep using 25565 (main moved with enable-proxy-mode.ps1 -MainPort 25566)
  proxy\start-proxy.ps1 -OnlineMode           # Mojang authentication on the proxy
  proxy\start-proxy.ps1 -Background
#>
param(
    [int]$ProxyPort = 25577,
    [string]$BindAddress = '0.0.0.0',
    # Main Paper server directory; its server.properties gives the default -MainHost / -MainPort
    [string]$ServerDir,
    [string]$MainHost,
    [int]$MainPort = 0,
    [int]$LimboPort = 25567,
    [int]$LimboRconPort = 25568,
    [switch]$OnlineMode,
    [switch]$Background,
    [string]$VelocityXmx = '512M',
    [string]$LimboXmx = '768M',
    [string]$JavaExe
)
. (Join-Path $PSScriptRoot 'lib\common.ps1')
$java = Get-JavaExe $JavaExe
if (-not $ServerDir) { $ServerDir = Join-Path $script:RootDir 'server' }
$mainProps = Join-Path $ServerDir 'server.properties'
if (-not $MainPort) {
    $p = Get-PropValue $mainProps 'server-port'
    $MainPort = if ($p) { [int]$p } else { 25565 }
}
if (-not $MainHost) {
    $ip = Get-PropValue $mainProps 'server-ip'
    $MainHost = if ($ip -and $ip -ne '0.0.0.0') { $ip } else { '127.0.0.1' }
}
if ($ProxyPort -eq $MainPort -and ($MainHost -eq '127.0.0.1' -or $MainHost -eq 'localhost')) {
    throw "Velocity et le serveur principal veulent tous les deux le port $ProxyPort : deplace le serveur principal (enable-proxy-mode.ps1 -MainPort 25566) ou choisis un autre -ProxyPort"
}

# --- setup if needed
$needSetup = -not (Test-Path (Join-Path $script:VelocityDir 'velocity.jar')) -or
             -not (Test-Path (Join-Path $script:VelocityDir 'plugins\vibe-reconnect.jar')) -or
             -not (Test-Path (Join-Path $script:LimboDir 'paper.jar')) -or
             -not (Test-Path $script:SecretFile)
if ($needSetup) {
    Write-Info 'Installation incomplete -> setup-proxy.ps1'
    & (Join-Path $PSScriptRoot 'setup-proxy.ps1')
}
$secret = Get-ForwardingSecret
$online = [bool]$OnlineMode

# --- is the main server ready for the proxy? (otherwise players would wait in the limbo forever)
$mainPaper = Join-Path $ServerDir 'config\paper-global.yml'
if (Test-Path $mainPaper) {
    $v = Get-PaperVelocity $mainPaper
    if ($v['enabled'] -ne 'true' -or $v['secret'] -ne $secret) {
        Write-Warn2 "ATTENTION : $ServerDir n'est pas en mode proxy (proxies.velocity desactive ou secret different)."
        Write-Warn2 "            Les joueurs ne pourront pas le rejoindre et resteront dans le limbo -> proxy\enable-proxy-mode.ps1 -ServerDir `"$ServerDir`""
    } elseif (($v['online-mode'] -eq 'true') -ne $online) {
        Write-Warn2 "online-mode du proxy ($online) different de proxies.velocity.online-mode du serveur principal ($($v['online-mode']))."
    }
} else {
    Write-Warn2 "$mainPaper introuvable : impossible de verifier que le serveur principal est en mode proxy."
}

# --- already running?
$velProc = Get-PidFileProcess 'velocity'
$limboProc = Get-PidFileProcess 'limbo'
if ($velProc) { throw "Velocity tourne deja (PID $($velProc.Id)). proxy\stop-proxy.ps1 pour l'arreter." }
if (-not (Test-PortFree $ProxyPort $BindAddress)) { throw "Le port $ProxyPort est deja utilise (serveur principal encore sur ce port ? -> enable-proxy-mode.ps1 -MainPort ...)" }

# --- render configs
Expand-Template (Join-Path $script:ProxyDir 'config\velocity.toml.tmpl') (Join-Path $script:VelocityDir 'velocity.toml') @{
    BIND        = "${BindAddress}:$ProxyPort"
    ONLINE_MODE = $online.ToString().ToLower()
    MAIN_ADDR   = "${MainHost}:$MainPort"
    LIMBO_ADDR  = "127.0.0.1:$LimboPort"
}
$rconPwdFile = Join-Path $script:LimboDir '.rcon-password'
if (-not (Test-Path $rconPwdFile)) { Write-TextFile $rconPwdFile (New-RandomSecret 20) }
Expand-Template (Join-Path $script:ProxyDir 'config\limbo-server.properties.tmpl') (Join-Path $script:LimboDir 'server.properties') @{
    LIMBO_PORT          = $LimboPort
    LIMBO_RCON_PORT     = $LimboRconPort
    LIMBO_RCON_PASSWORD = (Read-TextFile $rconPwdFile).Trim()
}
Set-PaperVelocity (Join-Path $script:LimboDir 'config\paper-global.yml') $true $online $secret
New-Item -ItemType Directory -Force $script:RunDir | Out-Null

# --- limbo (background)
if ($limboProc) {
    Write-Info "Limbo deja lance (PID $($limboProc.Id))"
} else {
    if (-not (Test-PortFree $LimboPort '127.0.0.1')) { throw "Le port du limbo $LimboPort est deja utilise" }
    $limboLog = Join-Path $script:RunDir 'limbo.log'
    Write-Info "Demarrage du limbo sur 127.0.0.1:$LimboPort ..."
    $limboProc = Start-Process -FilePath $java -WorkingDirectory $script:LimboDir -WindowStyle Hidden -PassThru `
        -RedirectStandardInput (Get-EmptyStdin) `
        -RedirectStandardOutput $limboLog -RedirectStandardError (Join-Path $script:RunDir 'limbo.err.log') `
        -ArgumentList @("-Xms256M", "-Xmx$LimboXmx", '-XX:+UseSerialGC', '-jar', 'paper.jar', '--nogui')
    Save-PidFile 'limbo' $limboProc
    if (-not (Wait-LogLine $limboLog 'Done \(' 180 $limboProc)) {
        throw "Le limbo n'a pas demarre, voir $limboLog"
    }
    Write-Ok "Limbo pret (PID $($limboProc.Id))"
}

# --- Velocity
$velArgs = @("-Xms128M", "-Xmx$VelocityXmx", '-XX:+UseG1GC', '-jar', 'velocity.jar')
if ($Background) {
    $velLog = Join-Path $script:RunDir 'velocity.log'
    $velProc = Start-Process -FilePath $java -WorkingDirectory $script:VelocityDir -WindowStyle Hidden -PassThru `
        -RedirectStandardInput (Get-EmptyStdin) -RedirectStandardOutput $velLog -RedirectStandardError (Join-Path $script:RunDir 'velocity.err.log') -ArgumentList $velArgs
    Save-PidFile 'velocity' $velProc
    # generous timeout: on a PC under heavy load (conference laptop + recording...) a JVM can take long to start
    if (-not (Wait-LogLine $velLog 'Done \(' 180 $velProc)) { throw "Velocity n'a pas demarre en 180 s, voir $velLog" }
    Write-Ok "Velocity pret sur ${BindAddress}:$ProxyPort (PID $($velProc.Id)) -> main=${MainHost}:$MainPort, limbo=127.0.0.1:$LimboPort"
    Write-Host '   Logs : proxy\run\velocity.log, proxy\run\limbo.log   Arret : proxy\stop-proxy.ps1'
    return
}

Write-Ok "Velocity sur ${BindAddress}:$ProxyPort -> main=${MainHost}:$MainPort, limbo=127.0.0.1:$LimboPort"
Write-Host '   Commandes utiles : vibeproxy status | vibeproxy return all | end (arrete le proxy et le limbo)'
Push-Location $script:VelocityDir
try {
    if ([Console]::IsInputRedirected) {
        # launched by a program with a piped stdin (not a console): see Get-EmptyStdin in lib\common.ps1
        Write-Warn2 "Entree standard redirigee : console Velocity desactivee (utilise -Background et stop-proxy.ps1 en automatisation)."
        $velProc = Start-Process -FilePath $java -ArgumentList $velArgs -NoNewWindow -PassThru -RedirectStandardInput (Get-EmptyStdin)
    } else {
        $velProc = Start-Process -FilePath $java -ArgumentList $velArgs -NoNewWindow -PassThru
    }
    $null = $velProc.Handle
    Save-PidFile 'velocity' $velProc
    while (-not $velProc.HasExited) { Start-Sleep -Milliseconds 300 }
} finally {
    Pop-Location
    if ($velProc -and -not $velProc.HasExited) {
        # Ctrl+C reached Velocity too; give it a moment to close cleanly
        if (-not $velProc.WaitForExit(10000)) { Stop-Process -Id $velProc.Id -Force -ErrorAction SilentlyContinue }
    }
    Remove-PidFile 'velocity'
    $limboProc = Get-PidFileProcess 'limbo'
    if ($limboProc) {
        Write-Info 'Arret du limbo...'
        Stop-PaperServer $limboProc $script:LimboDir 30
        Remove-PidFile 'limbo'
    }
    Write-Info 'Proxy arrete.'
}
