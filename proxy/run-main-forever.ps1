<#
.SYNOPSIS
  Runs the main Paper server and restarts it automatically whenever it exits
  (crash, "stop", "restart", killed JVM...). Behind the proxy, players wait in the limbo meanwhile.

.DESCRIPTION
  The server console is this window: you can type server commands as usual.
  To stop for good:
    - press Ctrl+C in this window (the server shuts down cleanly, no restart), or
    - create the stop file (default proxy\run\stop-main.flag) and then stop the server
      ("stop" in the console, or RCON). Example:
        New-Item proxy\run\stop-main.flag; node tests\tools\rcon.mjs stop
  Crash-loop protection: if the server dies within -QuickExitSec seconds -MaxQuickRestarts times
  in a row, the script waits -CooldownSec seconds before trying again.

.EXAMPLE
  proxy\run-main-forever.ps1
  proxy\run-main-forever.ps1 -Xmx 4G -ServerDir D:\autre\server
#>
param(
    [string]$ServerDir,
    [string]$Xms = '1G',
    [string]$Xmx = '4G',
    [string]$JavaExe,
    # Same JVM flags as scripts\start-server.ps1
    [string[]]$JvmArgs = @('-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-XX:MaxGCPauseMillis=200',
        '-XX:+UnlockExperimentalVMOptions', '-XX:+DisableExplicitGC', '-XX:+AlwaysPreTouch'),
    [string]$StopFile,
    [int]$RestartDelaySec = 2,
    [int]$QuickExitSec = 30,
    [int]$MaxQuickRestarts = 5,
    [int]$CooldownSec = 30,
    # Name of the PID file in proxy\run\ (lets several instances coexist, e.g. tests)
    [string]$PidName = 'main',
    # Start java hidden with output redirected to proxy\run\<PidName>.log (for automation/tests)
    [switch]$Hidden
)
. (Join-Path $PSScriptRoot 'lib\common.ps1')
if (-not $ServerDir) { $ServerDir = Join-Path $script:RootDir 'server' }
$ServerDir = (Resolve-Path $ServerDir).Path
if (-not $StopFile) { $StopFile = Join-Path $script:RunDir 'stop-main.flag' }
$java = Get-JavaExe $JavaExe
New-Item -ItemType Directory -Force $script:RunDir | Out-Null
if (Test-Path $StopFile) { Remove-Item -Force $StopFile }   # stale flag from a previous run
if (Get-PidFileProcess $PidName) { throw "Le serveur principal tourne deja (PID $((Get-PidFileProcess $PidName).Id))" }

$props = Join-Path $ServerDir 'server.properties'
$velocityOn = (Get-PaperVelocity (Join-Path $ServerDir 'config\paper-global.yml'))['enabled'] -eq 'true'
Write-Info "Serveur principal : $ServerDir (port $(Get-PropValue $props 'server-port'), mode proxy : $velocityOn)"
Write-Info "Redemarrage automatique actif. Arret definitif : Ctrl+C, ou creer $StopFile puis 'stop'."

$argsList = @("-Xms$Xms", "-Xmx$Xmx") + $JvmArgs + @('-jar', 'paper.jar', '--nogui')
$quick = 0
$run = 0
$proc = $null
$stopping = $false
try {
    while ($true) {
        $run++
        $started = Get-Date
        Write-Info ("Demarrage #{0} ({1})" -f $run, $started.ToString('HH:mm:ss'))
        if ($Hidden) {
            $proc = Start-Process -FilePath $java -ArgumentList $argsList -WorkingDirectory $ServerDir -PassThru -WindowStyle Hidden `
                -RedirectStandardInput (Get-EmptyStdin) `
                -RedirectStandardOutput (Join-Path $script:RunDir "$PidName.log") -RedirectStandardError (Join-Path $script:RunDir "$PidName.err.log")
        } elseif ([Console]::IsInputRedirected) {
            # started by a program with a piped stdin: private stdin for java (see Get-EmptyStdin in lib\common.ps1)
            $proc = Start-Process -FilePath $java -ArgumentList $argsList -WorkingDirectory $ServerDir -PassThru -NoNewWindow `
                -RedirectStandardInput (Get-EmptyStdin)
        } else {
            $proc = Start-Process -FilePath $java -ArgumentList $argsList -WorkingDirectory $ServerDir -PassThru -NoNewWindow
        }
        $null = $proc.Handle   # keeps ExitCode available on Windows PowerShell 5.1
        Save-PidFile $PidName $proc
        while (-not $proc.HasExited) { Start-Sleep -Milliseconds 250 }
        $code = $proc.ExitCode
        Remove-PidFile $PidName
        $uptime = [int]((Get-Date) - $started).TotalSeconds
        if (Test-Path $StopFile) {
            Remove-Item -Force $StopFile
            Write-Ok "Serveur arrete (code $code) et fichier stop present : fin."
            $stopping = $true
            break
        }
        if ($uptime -lt $QuickExitSec) { $quick++ } else { $quick = 0 }
        $delay = $RestartDelaySec
        if ($quick -ge $MaxQuickRestarts) {
            Write-Warn2 "Le serveur s'est arrete $quick fois en moins de $QuickExitSec s : pause de $CooldownSec s (crash en boucle ?)"
            $delay = $CooldownSec
            $quick = 0
        }
        Write-Warn2 "Serveur arrete (code $code, apres $uptime s) -> redemarrage dans $delay s (Ctrl+C pour annuler)"
        $deadline = (Get-Date).AddSeconds($delay)
        while ((Get-Date) -lt $deadline) {
            if (Test-Path $StopFile) { break }
            Start-Sleep -Milliseconds 200
        }
        if (Test-Path $StopFile) {
            Remove-Item -Force $StopFile
            Write-Ok 'Fichier stop detecte : pas de redemarrage.'
            $stopping = $true
            break
        }
    }
} finally {
    if ($proc -and -not $proc.HasExited) {
        # Ctrl+C: the server received it too and is saving; wait, then RCON stop, then kill.
        Write-Info 'Arret propre du serveur principal (pas de redemarrage)...'
        if ($Hidden) { Stop-PaperServer $proc $ServerDir 90 }   # hidden JVM did not get the Ctrl+C
        elseif (-not $proc.WaitForExit(60000)) { Stop-PaperServer $proc $ServerDir 60 }
    }
    Remove-PidFile $PidName
    if (-not $stopping) { Write-Info 'Superviseur arrete.' }
}
