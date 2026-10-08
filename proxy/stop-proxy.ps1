<#
.SYNOPSIS
  Stops the Velocity proxy and the limbo started by start-proxy.ps1 (uses proxy\run\*.pid,
  never touches any other java process).
#>
. (Join-Path $PSScriptRoot 'lib\common.ps1')
$vel = Get-PidFileProcess 'velocity'
if ($vel) {
    Write-Info "Arret de Velocity (PID $($vel.Id))"
    Stop-Process -Id $vel.Id -Force -ErrorAction SilentlyContinue
    [void]$vel.WaitForExit(10000)
} else { Write-Info 'Velocity ne tourne pas' }
Remove-PidFile 'velocity'
$limbo = Get-PidFileProcess 'limbo'
if ($limbo) {
    Write-Info "Arret du limbo (PID $($limbo.Id))"
    Stop-PaperServer $limbo $script:LimboDir 30
} else { Write-Info 'Le limbo ne tourne pas' }
Remove-PidFile 'limbo'
Write-Ok 'Termine.'
