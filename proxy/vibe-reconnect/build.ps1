<#
.SYNOPSIS
  Builds the VibeReconnect Velocity plugin with the portable JDK (runtime\jdk-25) against
  proxy\velocity\velocity.jar. -Deploy copies the jar into proxy\velocity\plugins\.
#>
param([switch]$Deploy)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$proxy = Split-Path -Parent $here
$root = Split-Path -Parent $proxy
$jdk = if ($env:JDK) { $env:JDK } else { Join-Path $root 'runtime\jdk-25' }
$javac = Join-Path $jdk 'bin\javac.exe'
$jar = Join-Path $jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac introuvable : $javac" }
$velocityJar = Join-Path $proxy 'velocity\velocity.jar'
if (-not (Test-Path $velocityJar)) { throw "Velocity introuvable : $velocityJar (lance proxy\setup-proxy.ps1)" }

$out = Join-Path $here 'build'
$classes = Join-Path $out 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $here 'src\main\java') | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
$argFile = Join-Path $out 'sources.txt'
[System.IO.File]::WriteAllLines($argFile, [string[]]$sources)

& $javac --release 21 -encoding UTF-8 -proc:none -Xlint:none -g -cp $velocityJar -d $classes "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac a echoue (code $LASTEXITCODE)" }
Copy-Item -Recurse -Force (Join-Path $here 'src\main\resources\*') $classes
$jarFile = Join-Path $out 'vibe-reconnect.jar'
if (Test-Path $jarFile) { Remove-Item -Force $jarFile }
& $jar --create --file $jarFile -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar a echoue (code $LASTEXITCODE)" }
Write-Host "OK: $jarFile"
if ($Deploy) {
    $plugins = Join-Path $proxy 'velocity\plugins'
    New-Item -ItemType Directory -Force $plugins | Out-Null
    Copy-Item -Force $jarFile (Join-Path $plugins 'vibe-reconnect.jar')
    Write-Host "Deployed: $(Join-Path $plugins 'vibe-reconnect.jar')"
}
$global:LASTEXITCODE = 0
