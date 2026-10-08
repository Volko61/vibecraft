# Démarre le serveur Paper 26.3 avec le JDK 25 portable du projet.
# Usage : .\scripts\start-server.ps1 [-Memory 4G]
param(
    [string]$Memory = "4G"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $root "runtime\jdk-25\bin\java.exe"
$server = Join-Path $root "server"

if (-not (Test-Path $java)) { throw "JDK 25 introuvable : $java" }
if (-not (Test-Path (Join-Path $server "paper.jar"))) { throw "paper.jar introuvable dans $server" }

Write-Host ""
Write-Host "  VibeCraft - serveur Minecraft live-coding" -ForegroundColor Magenta
Write-Host "  Connexion Minecraft : localhost (port 25565)" -ForegroundColor Gray
Write-Host "  Dashboard           : http://127.0.0.1:8765/" -ForegroundColor Gray
Write-Host "  Arrêt               : tape 'stop' dans cette console" -ForegroundColor Gray
Write-Host ""

Push-Location $server
try {
    & $java "-Xms1G" "-Xmx$Memory" "-XX:+UseG1GC" "-XX:+ParallelRefProcEnabled" "-XX:MaxGCPauseMillis=200" `
        "-XX:+UnlockExperimentalVMOptions" "-XX:+DisableExplicitGC" "-XX:+AlwaysPreTouch" `
        "-jar" "paper.jar" "--nogui"
}
finally {
    Pop-Location
}
