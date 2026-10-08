# Shared helpers for the VibeCraft proxy scripts (dot-source me).
# Compatible with Windows PowerShell 5.1 and PowerShell 7+. ASCII only on purpose.

$ErrorActionPreference = 'Stop'

$script:ProxyDir = Split-Path -Parent $PSScriptRoot
if ((Split-Path -Leaf $PSScriptRoot) -ne 'lib') { $script:ProxyDir = $PSScriptRoot }
$script:RootDir = Split-Path -Parent $script:ProxyDir
$script:RunDir = Join-Path $script:ProxyDir 'run'
$script:VelocityDir = Join-Path $script:ProxyDir 'velocity'
$script:LimboDir = Join-Path $script:ProxyDir 'limbo'
$script:SecretFile = Join-Path $script:VelocityDir 'forwarding.secret'
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Info([string]$msg) { Write-Host "[vibeproxy] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg) { Write-Host "[vibeproxy] $msg" -ForegroundColor Green }
function Write-Warn2([string]$msg) { Write-Host "[vibeproxy] $msg" -ForegroundColor Yellow }

function Get-JavaExe {
    param([string]$JavaExe)
    if ($JavaExe) { return $JavaExe }
    $candidate = Join-Path $script:RootDir 'runtime\jdk-25\bin\java.exe'
    if (Test-Path $candidate) { return $candidate }
    return 'java'
}

function Read-TextFile([string]$path) {
    return [System.IO.File]::ReadAllText($path)
}

function Write-TextFile([string]$path, [string]$text) {
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    [System.IO.File]::WriteAllText($path, $text, $script:Utf8NoBom)
}

function New-RandomSecret([int]$length = 32) {
    $chars = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'.ToCharArray()
    $bytes = New-Object byte[] $length
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $sb = New-Object System.Text.StringBuilder
    foreach ($b in $bytes) { [void]$sb.Append($chars[$b % $chars.Length]) }
    return $sb.ToString()
}

# Returns the Velocity forwarding secret, creating proxy/velocity/forwarding.secret if needed.
function Get-ForwardingSecret {
    if (-not (Test-Path $script:SecretFile)) {
        Write-TextFile $script:SecretFile (New-RandomSecret 32)
        Write-Info "Nouveau secret de forwarding cree : $script:SecretFile"
    }
    return (Read-TextFile $script:SecretFile).Trim()
}

# Text files as a list of {Text, Eol} so that edits keep each line's own ending: on Windows Paper
# writes paper-global.yml with a CRLF header and an LF body; restoring must give back the same bytes.
function Read-LineList([string]$file) {
    $list = New-Object System.Collections.Generic.List[object]
    if (Test-Path $file) {
        foreach ($m in [regex]::Matches((Read-TextFile $file), '([^\r\n]*)(\r\n|\n|\r|$)')) {
            if ($m.Length -eq 0) { continue }
            $list.Add([pscustomobject]@{ Text = $m.Groups[1].Value; Eol = $m.Groups[2].Value })
        }
    }
    return ,$list
}

function Add-LineAt($lines, [int]$index, [string]$text) {
    $eol = "`n"
    if ($index -gt 0) {
        $prev = $lines[$index - 1]
        if ($prev.Eol) { $eol = $prev.Eol } else { $prev.Eol = "`n" }
    }
    $lines.Insert($index, [pscustomobject]@{ Text = $text; Eol = $eol })
}

function Write-LineList([string]$file, $lines) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($l in $lines) { [void]$sb.Append($l.Text).Append($l.Eol) }
    Write-TextFile $file $sb.ToString()
}

# ---------------------------------------------------------------- server.properties

function Get-PropValue([string]$file, [string]$key) {
    if (-not (Test-Path $file)) { return $null }
    foreach ($line in [System.IO.File]::ReadAllLines($file)) {
        if ($line -match ('^\s*' + [regex]::Escape($key) + '\s*[=:]\s*(.*)$')) { return $Matches[1] }
    }
    return $null
}

# Sets (or appends) key=value, keeping every other line untouched. $value $null => line removed.
function Set-PropValue([string]$file, [string]$key, $value) {
    $lines = Read-LineList $file
    $pattern = '^\s*' + [regex]::Escape($key) + '\s*[=:]'
    $found = $false
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i].Text -match $pattern) {
            if ($null -eq $value -or $found) { $lines.RemoveAt($i) }
            else { $lines[$i].Text = "$key=$value"; $found = $true }
        }
    }
    if (-not $found -and $null -ne $value) { Add-LineAt $lines $lines.Count "$key=$value" }
    Write-LineList $file $lines
}

# ---------------------------------------------------------------- paper-global.yml (proxies.velocity)

function Get-PaperVelocity([string]$file) {
    $result = @{ enabled = $null; 'online-mode' = $null; secret = $null }
    if (-not (Test-Path $file)) { return $result }
    $lines = [System.IO.File]::ReadAllLines($file)
    $inProxies = $false; $inVelocity = $false
    foreach ($line in $lines) {
        if ($line -match '^proxies:\s*$') { $inProxies = $true; $inVelocity = $false; continue }
        if ($inProxies -and $line -match '^\S') { $inProxies = $false; $inVelocity = $false }
        if (-not $inProxies) { continue }
        if ($line -match '^  (\S[^:]*):\s*$') { $inVelocity = ($Matches[1] -eq 'velocity'); continue }
        if ($inVelocity -and $line -match '^    (enabled|online-mode|secret):\s*(.*)$') {
            $key = $Matches[1]
            $v = $Matches[2].Trim()
            if ($v -match "^'(.*)'$") { $v = $Matches[1] -replace "''", "'" }
            elseif ($v -match '^"(.*)"$') { $v = $Matches[1] }
            $result[$key] = $v
        }
    }
    return $result
}

# Edits proxies.velocity.{enabled,online-mode,secret} in a Paper paper-global.yml, line based
# (keeps comments/other settings/line endings). Missing blocks/keys are created.
function Set-PaperVelocity([string]$file, [bool]$enabled, [bool]$onlineMode, [string]$secret) {
    if (-not (Test-Path $file)) { throw "Fichier introuvable : $file (demarre le serveur une fois pour le generer)" }
    $lines = Read-LineList $file
    $want = [ordered]@{
        'enabled'     = $enabled.ToString().ToLower()
        'online-mode' = $onlineMode.ToString().ToLower()
        'secret'      = "'" + ($secret -replace "'", "''") + "'"
    }
    $proxiesIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].Text -match '^proxies:\s*$') { $proxiesIdx = $i; break } }
    if ($proxiesIdx -lt 0) {
        Add-LineAt $lines $lines.Count 'proxies:'
        Add-LineAt $lines $lines.Count '  velocity:'
        foreach ($k in $want.Keys) { Add-LineAt $lines $lines.Count "    ${k}: $($want[$k])" }
    } else {
        $end = $lines.Count
        for ($i = $proxiesIdx + 1; $i -lt $lines.Count; $i++) { if ($lines[$i].Text -match '^\S') { $end = $i; break } }
        $velIdx = -1
        for ($i = $proxiesIdx + 1; $i -lt $end; $i++) { if ($lines[$i].Text -match '^  velocity:\s*$') { $velIdx = $i; break } }
        if ($velIdx -lt 0) {
            $at = $proxiesIdx + 1
            Add-LineAt $lines $at '  velocity:'
            foreach ($k in $want.Keys) { $at++; Add-LineAt $lines $at "    ${k}: $($want[$k])" }
        } else {
            $velEnd = $end
            for ($i = $velIdx + 1; $i -lt $end; $i++) { if ($lines[$i].Text -match '^  \S') { $velEnd = $i; break } }
            foreach ($k in $want.Keys) {
                $done = $false
                for ($i = $velIdx + 1; $i -lt $velEnd; $i++) {
                    if ($lines[$i].Text -match ('^    ' + [regex]::Escape($k) + ':')) { $lines[$i].Text = "    ${k}: $($want[$k])"; $done = $true; break }
                }
                if (-not $done) { Add-LineAt $lines ($velIdx + 1) "    ${k}: $($want[$k])"; $velEnd++ }
            }
        }
    }
    Write-LineList $file $lines
}

# ---------------------------------------------------------------- templates

function Expand-Template([string]$templateFile, [string]$outFile, [hashtable]$values) {
    $text = Read-TextFile $templateFile
    foreach ($k in $values.Keys) { $text = $text.Replace('{{' + $k + '}}', [string]$values[$k]) }
    if ($text -match '\{\{[A-Z_]+\}\}') { throw "Placeholder non remplace dans $templateFile : $($Matches[0])" }
    Write-TextFile $outFile $text
}

# ---------------------------------------------------------------- processes

function Test-PortFree([int]$port, [string]$address = '0.0.0.0') {
    try {
        $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Parse($address), $port)
        $l.Start(); $l.Stop(); return $true
    } catch { return $false }
}

function Test-PortOpen([int]$port, [string]$address = '127.0.0.1') {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $c.BeginConnect($address, $port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne(500)) { return $false }
        $c.EndConnect($iar); return $true
    } catch { return $false } finally { $c.Close() }
}

# Empty file used as stdin for the hidden java processes. If they inherited our stdin while it is a
# pipe (script launched by another program, e.g. node/CI), the JVM can block at startup until this
# PowerShell exits (Windows serialises calls on a synchronous pipe handle that has a pending read):
# Velocity then never prints "Done". A private, empty stdin avoids that.
function Get-EmptyStdin {
    $f = Join-Path $script:RunDir 'empty.stdin'
    if (-not (Test-Path $f)) { Write-TextFile $f '' }
    return $f
}

# PID files: "<pid>`n<startTimeTicks>" so we never kill a recycled PID.
function Save-PidFile([string]$name, [System.Diagnostics.Process]$proc) {
    if (-not (Test-Path $script:RunDir)) { New-Item -ItemType Directory -Force $script:RunDir | Out-Null }
    Write-TextFile (Join-Path $script:RunDir "$name.pid") ("{0}`n{1}`n" -f $proc.Id, $proc.StartTime.ToUniversalTime().Ticks)
}

function Get-PidFileProcess([string]$name) {
    $f = Join-Path $script:RunDir "$name.pid"
    if (-not (Test-Path $f)) { return $null }
    $parts = (Read-TextFile $f).Split("`n") | Where-Object { $_.Trim() }
    if ($parts.Count -lt 1) { return $null }
    $p = Get-Process -Id ([int]$parts[0]) -ErrorAction SilentlyContinue
    if (-not $p) { return $null }
    if ($parts.Count -ge 2) {
        try { if ($p.StartTime.ToUniversalTime().Ticks -ne [long]$parts[1]) { return $null } } catch { return $null }
    }
    return $p
}

function Remove-PidFile([string]$name) {
    $f = Join-Path $script:RunDir "$name.pid"
    if (Test-Path $f) { Remove-Item $f -Force }
}

function Wait-LogLine([string]$file, [string]$pattern, [int]$timeoutSec, [System.Diagnostics.Process]$proc) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($proc -and $proc.HasExited) { return $false }
        if (Test-Path $file) {
            try {
                $fs = [System.IO.File]::Open($file, 'Open', 'Read', 'ReadWrite')
                try { $text = (New-Object System.IO.StreamReader($fs)).ReadToEnd() } finally { $fs.Close() }
                if ($text -match $pattern) { return $true }
            } catch { }
        }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

# ---------------------------------------------------------------- RCON (minimal client)

function Invoke-Rcon([string]$address, [int]$port, [string]$password, [string]$command, [int]$timeoutMs = 5000) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($address, $port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($timeoutMs)) { throw "RCON: timeout de connexion ($address`:$port)" }
        $client.EndConnect($iar)
        $client.ReceiveTimeout = $timeoutMs
        $stream = $client.GetStream()
        $send = {
            param([int]$id, [int]$type, [string]$body)
            $payload = [System.Text.Encoding]::UTF8.GetBytes($body)
            $buf = New-Object byte[] (14 + $payload.Length)
            [BitConverter]::GetBytes([int](10 + $payload.Length)).CopyTo($buf, 0)
            [BitConverter]::GetBytes($id).CopyTo($buf, 4)
            [BitConverter]::GetBytes($type).CopyTo($buf, 8)
            $payload.CopyTo($buf, 12)
            $stream.Write($buf, 0, $buf.Length)
        }
        $read = {
            $hdr = New-Object byte[] 4; $n = 0
            while ($n -lt 4) { $r = $stream.Read($hdr, $n, 4 - $n); if ($r -le 0) { throw 'RCON: connexion fermee' }; $n += $r }
            $len = [BitConverter]::ToInt32($hdr, 0)
            $data = New-Object byte[] $len; $n = 0
            while ($n -lt $len) { $r = $stream.Read($data, $n, $len - $n); if ($r -le 0) { throw 'RCON: connexion fermee' }; $n += $r }
            return @{ id = [BitConverter]::ToInt32($data, 0); body = [System.Text.Encoding]::UTF8.GetString($data, 8, [Math]::Max(0, $len - 10)) }
        }
        & $send 1 3 $password
        $auth = & $read
        if ($auth.id -eq -1) { throw 'RCON: mot de passe refuse' }
        & $send 2 2 $command
        try { $resp = & $read; return ($resp.body -replace ([string][char]0xA7 + '.'), '') } catch { return '' }
    } finally { $client.Close() }
}

# Stops a Paper server: RCON "stop" if possible, then waits, then kills as a last resort.
function Stop-PaperServer([System.Diagnostics.Process]$proc, [string]$serverDir, [int]$waitSec = 90) {
    if (-not $proc -or $proc.HasExited) { return }
    $props = Join-Path $serverDir 'server.properties'
    $rconOn = (Get-PropValue $props 'enable-rcon') -eq 'true'
    if ($rconOn) {
        try {
            $ip = Get-PropValue $props 'server-ip'; if (-not $ip) { $ip = '127.0.0.1' }
            [void](Invoke-Rcon $ip ([int](Get-PropValue $props 'rcon.port')) (Get-PropValue $props 'rcon.password') 'stop')
        } catch { Write-Warn2 "RCON stop impossible ($($_.Exception.Message))" }
    }
    if (-not $proc.WaitForExit($waitSec * 1000)) {
        Write-Warn2 "Le serveur ne s'arrete pas, arret force (PID $($proc.Id))"
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}
