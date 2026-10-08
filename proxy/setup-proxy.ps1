<#
.SYNOPSIS
  One-time (idempotent) installation of the VibeCraft proxy safety net:
  Velocity, the limbo backend, ViaVersion/ViaBackwards for the limbo, the forwarding secret and
  the VibeReconnect plugin. Safe to run again (only missing/outdated pieces are refreshed).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File proxy\setup-proxy.ps1
#>
param(
    # Paper server used as source for paper.jar / libraries / versions / cache / eula.txt / config
    [string]$ServerDir,
    # Velocity build supporting Minecraft 26.1 - 26.3 (Velocity 4.2.0 build 30)
    [string]$VelocityUrl = 'https://fill-data.papermc.io/v1/objects/35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8/velocity-4.2.0-30.jar',
    [string]$VelocitySha256 = '35a5596a5468a035d8a32c8de5ebb0dc6b8d8f0cc3ff5169d514aca762af8aa8',
    [string]$ViaVersionUrl = 'https://cdn.modrinth.com/data/P1OZGk5p/versions/TEgYlalY/ViaVersion-5.12.1-SNAPSHOT.jar',
    [string]$ViaBackwardsUrl = 'https://cdn.modrinth.com/data/NpvuJQoq/versions/w6P38zDf/ViaBackwards-5.12.1-SNAPSHOT.jar',
    # Do not install ViaVersion/ViaBackwards in the limbo (only 26.3 clients then)
    [switch]$NoVia,
    # Re-copy paper.jar/libraries/... into the limbo even if they look up to date
    [switch]$Force
)
. (Join-Path $PSScriptRoot 'lib\common.ps1')
if (-not $ServerDir) { $ServerDir = Join-Path $script:RootDir 'server' }
$ServerDir = (Resolve-Path $ServerDir).Path
$downloads = Join-Path $script:ProxyDir 'downloads'
New-Item -ItemType Directory -Force $downloads, $script:VelocityDir, (Join-Path $script:VelocityDir 'plugins'), $script:LimboDir, $script:RunDir | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

# 1. Velocity ------------------------------------------------------------------------------
$velJar = Join-Path $script:VelocityDir 'velocity.jar'
$needVel = -not (Test-Path $velJar)
if (-not $needVel -and $VelocitySha256) {
    $needVel = ((Get-FileHash $velJar -Algorithm SHA256).Hash.ToLower() -ne $VelocitySha256.ToLower())
}
if ($needVel) {
    Write-Info "Telechargement de Velocity : $VelocityUrl"
    Invoke-WebRequest -Uri $VelocityUrl -OutFile "$velJar.part" -UseBasicParsing
    if ($VelocitySha256 -and (Get-FileHash "$velJar.part" -Algorithm SHA256).Hash.ToLower() -ne $VelocitySha256.ToLower()) {
        Remove-Item "$velJar.part"; throw 'Velocity : SHA-256 incorrect, telechargement rejete'
    }
    Move-Item -Force "$velJar.part" $velJar
}
Write-Ok "Velocity : $velJar"

# 2. Forwarding secret ------------------------------------------------------------------------
[void](Get-ForwardingSecret)

# 3. Limbo backend (minimal Paper copied from the main server) ------------------------------------
if (-not (Test-Path (Join-Path $ServerDir 'paper.jar'))) { throw "paper.jar introuvable dans $ServerDir" }
$srcJar = Join-Path $ServerDir 'paper.jar'
$dstJar = Join-Path $script:LimboDir 'paper.jar'
$refresh = $Force -or -not (Test-Path $dstJar) -or ((Get-FileHash $srcJar).Hash -ne (Get-FileHash $dstJar).Hash)
if ($refresh) {
    Write-Info "Copie de Paper vers le limbo depuis $ServerDir ..."
    Copy-Item -Force $srcJar $dstJar
    foreach ($d in 'libraries', 'versions', 'cache') {
        $src = Join-Path $ServerDir $d
        $dst = Join-Path $script:LimboDir $d
        if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
        if (Test-Path $src) { Copy-Item -Recurse -Force $src $dst }
    }
}
$eulaSrc = Join-Path $ServerDir 'eula.txt'
$eulaDst = Join-Path $script:LimboDir 'eula.txt'
if (-not (Test-Path $eulaDst)) {
    if ((Test-Path $eulaSrc) -and ((Read-TextFile $eulaSrc) -match 'eula\s*=\s*true')) {
        Copy-Item $eulaSrc $eulaDst
    } else {
        Write-Warn2 "EULA Minecraft non acceptee dans $ServerDir : accepte-la (eula=true) puis relance ce script."
    }
}
$limboConfig = Join-Path $script:LimboDir 'config'
New-Item -ItemType Directory -Force $limboConfig, (Join-Path $script:LimboDir 'plugins') | Out-Null
foreach ($f in 'paper-global.yml', 'paper-world-defaults.yml') {
    $dst = Join-Path $limboConfig $f
    $src = Join-Path $ServerDir "config\$f"
    if (-not (Test-Path $dst) -and (Test-Path $src)) { Copy-Item $src $dst }
}
if (-not (Test-Path (Join-Path $limboConfig 'paper-global.yml'))) {
    throw "config\paper-global.yml introuvable dans $ServerDir : demarre le serveur principal une fois, puis relance ce script."
}
$rconPwdFile = Join-Path $script:LimboDir '.rcon-password'
if (-not (Test-Path $rconPwdFile)) { Write-TextFile $rconPwdFile (New-RandomSecret 20) }

# 4. ViaVersion / ViaBackwards in the limbo (so older clients accepted by "main" also fit in the limbo)
if (-not $NoVia) {
    foreach ($pair in @(@('ViaVersion.jar', $ViaVersionUrl), @('ViaBackwards.jar', $ViaBackwardsUrl))) {
        $cached = Join-Path $downloads $pair[0]
        if (-not (Test-Path $cached)) {
            Write-Info "Telechargement de $($pair[0])"
            Invoke-WebRequest -Uri $pair[1] -OutFile $cached -UseBasicParsing
        }
        Copy-Item -Force $cached (Join-Path $script:LimboDir "plugins\$($pair[0])")
    }
    Write-Ok 'ViaVersion + ViaBackwards installes dans le limbo'
}

# 5. VibeReconnect plugin ------------------------------------------------------------------------
& (Join-Path $script:ProxyDir 'vibe-reconnect\build.ps1') -Deploy
if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw 'Compilation de VibeReconnect en echec' }

Write-Ok 'Installation terminee. Etapes suivantes :'
Write-Host '   1) proxy\enable-proxy-mode.ps1     (serveur principal derriere le proxy : 127.0.0.1:25565)'
Write-Host '   2) proxy\start-proxy.ps1           (Velocity :25577 + limbo 127.0.0.1:25567)'
Write-Host '   3) proxy\run-main-forever.ps1      (serveur principal, redemarre tout seul)'
Write-Host '   Details : proxy\README.md'
