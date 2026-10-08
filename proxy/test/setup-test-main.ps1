<#
.SYNOPSIS
  TEST ONLY: creates proxy\test-main, a throwaway Paper backend used by proxy\test\failover-test.mjs
  (127.0.0.1:25581, RCON 25591, flat world), already switched to proxy mode with
  enable-proxy-mode.ps1. The real server (..\server) is only READ: paper.jar, libraries, versions,
  cache, eula.txt, config\paper-*.yml and the ViaVersion/ViaBackwards jars are copied from it.
  Also builds and installs the TEST plugin VibeFreezeTest ("/vibefreeze <s>" blocks the main thread).

.EXAMPLE
  pwsh proxy\test\setup-test-main.ps1
  pwsh proxy\test\setup-test-main.ps1 -Force      # wipe and recreate (world included)
#>
param(
    [string]$ServerDir,
    [string]$TargetDir,
    [int]$Port = 25581,
    [int]$RconPort = 25591,
    [switch]$Force
)
. (Join-Path $PSScriptRoot '..\lib\common.ps1')
if (-not $ServerDir) { $ServerDir = Join-Path $script:RootDir 'server' }
if (-not $TargetDir) { $TargetDir = Join-Path $script:ProxyDir 'test-main' }
$ServerDir = (Resolve-Path $ServerDir).Path
foreach ($f in 'paper.jar', 'eula.txt', 'config\paper-global.yml') {
    if (-not (Test-Path (Join-Path $ServerDir $f))) { throw "$f introuvable dans $ServerDir (demarre le vrai serveur une fois)" }
}
if ((Read-TextFile (Join-Path $ServerDir 'eula.txt')) -notmatch 'eula\s*=\s*true') { throw "EULA non acceptee dans $ServerDir" }
if ($Force -and (Test-Path $TargetDir)) {
    if (Test-PortOpen $Port) { throw "Quelque chose ecoute sur le port $Port : arrete le serveur de test avant -Force" }
    Remove-Item -Recurse -Force $TargetDir
}
New-Item -ItemType Directory -Force $TargetDir, (Join-Path $TargetDir 'config'), (Join-Path $TargetDir 'plugins') | Out-Null
$TargetDir = (Resolve-Path $TargetDir).Path

# 1. Paper runtime (copied from the real server, never modified there)
if (-not (Test-Path (Join-Path $TargetDir 'paper.jar')) -or
    (Get-FileHash (Join-Path $ServerDir 'paper.jar')).Hash -ne (Get-FileHash (Join-Path $TargetDir 'paper.jar')).Hash) {
    Write-Info "Copie de Paper depuis $ServerDir ..."
    Copy-Item -Force (Join-Path $ServerDir 'paper.jar') $TargetDir
    foreach ($d in 'libraries', 'versions', 'cache') {
        $dst = Join-Path $TargetDir $d
        if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
        if (Test-Path (Join-Path $ServerDir $d)) { Copy-Item -Recurse -Force (Join-Path $ServerDir $d) $dst }
    }
}
Copy-Item -Force (Join-Path $ServerDir 'eula.txt') $TargetDir
foreach ($f in 'paper-global.yml', 'paper-world-defaults.yml') {
    $dst = Join-Path $TargetDir "config\$f"
    if (-not (Test-Path $dst) -and (Test-Path (Join-Path $ServerDir "config\$f"))) { Copy-Item (Join-Path $ServerDir "config\$f") $dst }
}

# 2. server.properties (only the keys that matter; Paper fills in the rest on first start)
$props = Join-Path $TargetDir 'server.properties'
$want = [ordered]@{
    'server-ip' = '127.0.0.1'; 'server-port' = $Port; 'motd' = 'VibeCraft TEST main'
    'enable-rcon' = 'true'; 'rcon.port' = $RconPort; 'rcon.password' = 'testmain-rcon'; 'broadcast-rcon-to-ops' = 'false'
    'online-mode' = 'false'; 'enforce-secure-profile' = 'false'; 'level-name' = 'world'; 'level-type' = 'minecraft\:flat'
    'generate-structures' = 'false'; 'difficulty' = 'peaceful'; 'spawn-protection' = '0'; 'view-distance' = '4'
    'simulation-distance' = '4'; 'pause-when-empty-seconds' = '-1'; 'sync-chunk-writes' = 'false'
    'enable-code-of-conduct' = 'false'; 'management-server-enabled' = 'false'
    # a fresh 26.3 server.properties defaults to white-list=true: the bots would be refused
    'white-list' = 'false'; 'enforce-whitelist' = 'false'
}
foreach ($k in $want.Keys) { Set-PropValue $props $k $want[$k] }

# 3. ViaVersion + ViaBackwards (mineflayer speaks 26.1)
foreach ($name in 'ViaVersion', 'ViaBackwards') {
    $src = Join-Path $script:ProxyDir "downloads\$name.jar"
    if (-not (Test-Path $src)) { $src = Get-ChildItem (Join-Path $ServerDir 'plugins') -Filter "$name*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName }
    if ($src) { Copy-Item -Force $src (Join-Path $TargetDir "plugins\$name.jar") }
    else { Write-Warn2 "$name.jar introuvable (lance d'abord proxy\setup-proxy.ps1)" }
}

# 4. VibeFreezeTest (TEST plugin) built against the Paper API of the real server
$fp = Join-Path $PSScriptRoot 'freeze-plugin'
$jdk = Join-Path $script:RootDir 'runtime\jdk-25\bin'
$cp = (Get-ChildItem -Recurse (Join-Path $ServerDir 'libraries') -Include 'paper-api-*.jar', 'adventure-api-*.jar', 'adventure-key-*.jar', 'examination-api-*.jar' |
       ForEach-Object FullName) -join ';'
$build = Join-Path $fp 'build'
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force (Join-Path $build 'classes') | Out-Null
& (Join-Path $jdk 'javac.exe') --release 25 -encoding UTF-8 -proc:none -Xlint:none -cp $cp -d (Join-Path $build 'classes') `
    (Join-Path $fp 'src\dev\vibecraft\testfreeze\FreezePlugin.java')
if ($LASTEXITCODE -ne 0) { throw 'Compilation de VibeFreezeTest en echec' }
Copy-Item (Join-Path $fp 'src\plugin.yml') (Join-Path $build 'classes')
& (Join-Path $jdk 'jar.exe') --create --file (Join-Path $TargetDir 'plugins\VibeFreezeTest.jar') -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar VibeFreezeTest en echec' }

# 5. proxy mode (modern forwarding, same secret as proxy\velocity\forwarding.secret)
& (Join-Path $script:ProxyDir 'enable-proxy-mode.ps1') -ServerDir $TargetDir -MainPort $Port
Write-Ok "Backend de test pret : $TargetDir (127.0.0.1:$Port, RCON $RconPort). Lance : node proxy\test\failover-test.mjs --start-proxy --stop-proxy"
