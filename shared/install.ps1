# install.ps1 — Installs the PegasusBridge daemon for the current user and points
# every theme that can use it at the data root. The Windows counterpart of
# install.sh, same contract, same flags where they still mean something.
#
#   .\install.ps1 [-Prefix <dir>] [-NoService]
#                 [-Theme <dir>]... [-PegasusConfig <dir>]... [-NoThemes]
#                 [-LinkOnly] [-Uninstall]
#
# Everything it touches is under the current user, and -Uninstall reverses it:
#   * copies the self-contained bundle to <Prefix>\app
#   * writes the pointer files themes need to find the data root
#   * registers a Scheduled Task that starts the daemon at logon
#
# Differences from install.sh, and why
# ------------------------------------
# * No on-demand mode. On Linux systemd holds the socket and starts the daemon on
#   the first connection, because a JVM cannot adopt an inherited listening
#   descriptor. Windows has no equivalent chain, so the only honest option is
#   always-on at logon.
# * A Scheduled Task rather than a service. A service runs as SYSTEM or a service
#   account, which is the wrong user profile — and the data root, the credentials
#   and the caches all live in the user's profile. The task is per-user, needs no
#   administrator, and shows up in Task Scheduler where it can be seen and removed.
# * The daemon is stopped before the bundle is replaced. Windows locks a running
#   executable, so on an upgrade the copy would fail halfway and leave a mixture of
#   two versions.
#
# Pointing themes at the Bridge
# -----------------------------
# A theme is QML: it cannot expand ~ nor read the environment, so the absolute data
# root has to be written where the theme can reach it by relative path. That file is
# `bridge.json`, and it is written in two places, both of which a theme is expected
# to try:
#
#   <pegasus config>\bridge.json   one pointer shared by every theme, always written
#   <theme>\bridge.json            a copy inside each theme that asks for it
#
# A theme opts in by naming the pointer in its own sources — ReStory does it in
# components\data\BridgeApi.js. A theme that never reads the file gets none.
#
# `daemon.json` is NOT written here. The daemon writes it, last, once it is actually
# listening: its presence is what tells a theme the server accepts connections.

param(
    [string]   $Prefix        = "$env:LOCALAPPDATA\pegasus-bridge",
    [string[]] $Theme         = @(),
    [string[]] $PegasusConfig = @(),
    [switch]   $NoThemes,
    [switch]   $LinkOnly,
    [switch]   $NoService,
    [switch]   $Uninstall
)

$ErrorActionPreference = "Stop"
$Here      = $PSScriptRoot
$TaskName  = "PegasusBridge"
$TaskDesc  = "Runs the PegasusBridge daemon on 127.0.0.1 so Pegasus Frontend themes can reach RetroAchievements and the scrapers. Installed by install.ps1; remove with 'install.ps1 -Uninstall'."
$Prefix    = $Prefix.TrimEnd('\')

# ── Pointing themes at the data root ───────────────────────────────────────

# Where Pegasus keeps its configuration on Windows. Measured, not assumed: a
# running Pegasus reports `%LOCALAPPDATA%\pegasus-frontend` in its own log, and
# there is no portable config beside the executable. One directory, unlike the
# three XDG/Flatpak candidates install.sh has to consider.
function Get-ConfigDirs {
    $out  = @()
    $seen = @{}
    foreach ($d in ($PegasusConfig + @("$env:LOCALAPPDATA\pegasus-frontend"))) {
        if (-not $d) { continue }
        $d = $d.TrimEnd('\')
        if (-not (Test-Path $d)) { continue }
        $real = (Resolve-Path $d).Path
        if ($seen.ContainsKey($real)) { continue }
        $seen[$real] = $true
        $out += $d
    }
    return $out
}

function Test-ThemeUsesBridge([string]$dir) {
    $files = Get-ChildItem $dir -Recurse -File -Include *.qml, *.js -EA SilentlyContinue |
             Where-Object { $_.FullName -notmatch '\\\.git\\' }
    foreach ($f in $files) {
        if (Select-String -Path $f.FullName -Pattern 'bridge\.json' -Quiet -EA SilentlyContinue) { return $true }
    }
    return $false
}

function Write-Pointer([string]$dir) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $json = [ordered]@{
        schemaVersion = 1
        dataRoot      = $Prefix
        app           = (Join-Path $Prefix "app")
    } | ConvertTo-Json
    Set-Content -Path (Join-Path $dir "bridge.json") -Value $json -Encoding utf8
}

# Only ever removes a pointer that names *this* data root. A second install with a
# different -Prefix, or a file someone wrote by hand, is left alone.
function Remove-Pointer([string]$dir) {
    $f = Join-Path $dir "bridge.json"
    if (-not (Test-Path $f)) { return }
    try { $cfg = Get-Content $f -Raw | ConvertFrom-Json } catch { return }
    if ($cfg.dataRoot -ne $Prefix) { return }
    Remove-Item $f -Force
    Write-Host "    removed $f" -ForegroundColor DarkGray
}

function Invoke-Pointers([string]$Mode) {
    foreach ($cfg in Get-ConfigDirs) {
        if ($Mode -eq "write") {
            Write-Pointer $cfg
            Write-Host "==> shared pointer: $cfg\bridge.json"
        } else {
            Remove-Pointer $cfg
        }

        $themesDir = Join-Path $cfg "themes"
        if (-not (Test-Path $themesDir)) { continue }
        foreach ($t in (Get-ChildItem $themesDir -Directory -Force -EA SilentlyContinue)) {
            if (-not (Test-ThemeUsesBridge $t.FullName)) { continue }
            if ($Mode -ne "write") { Remove-Pointer $t.FullName; continue }

            # A theme directory is often a junction or symlink to a working copy, and
            # writing through it lands inside that repository. Those get the shared
            # pointer only; -Theme overrides this for a theme that cannot read it.
            if ($t.LinkType) {
                Write-Host "    $($t.Name): $($t.LinkType) -> $($t.Target)"
                Write-Host "         left untouched; it reads the shared pointer above." -ForegroundColor DarkGray
                Write-Host "         If it only looks in its own directory, re-run with:" -ForegroundColor DarkGray
                Write-Host "           .\install.ps1 -LinkOnly -Theme `"$($t.FullName)`"" -ForegroundColor DarkGray
                continue
            }
            Write-Pointer $t.FullName
            Write-Host "    $($t.Name): pointed at $Prefix"
        }
    }

    foreach ($t in $Theme) {
        $t = $t.TrimEnd('\')
        if ($Mode -ne "write") { Remove-Pointer $t; continue }
        if (-not (Test-Path $t)) { throw "theme directory not found: $t" }
        Write-Pointer $t
        Write-Host "==> pointed $(Split-Path $t -Leaf) at $Prefix"
    }
}

function Update-Themes {
    if ($NoThemes) { return }
    # The Bridge can be installed before Pegasus has ever run, in which case there is
    # no config directory yet. Create the standard one so the pointer is already
    # waiting: Pegasus would create the same directory itself.
    if ((Get-ConfigDirs).Count -eq 0) {
        New-Item -ItemType Directory -Force -Path "$env:LOCALAPPDATA\pegasus-frontend" | Out-Null
    }
    Invoke-Pointers "write"
}

# ── The Scheduled Task ─────────────────────────────────────────────────────

function Stop-Daemon {
    try { Stop-ScheduledTask -TaskName $TaskName -EA SilentlyContinue } catch { }
    # The task launches javaw.exe from inside the bundle; that is the process holding
    # the files we are about to replace. Match on the path so a JVM belonging to
    # anything else on the machine is left alone.
    $appDir = Join-Path $Prefix "app"
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" -EA SilentlyContinue |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($appDir, "OrdinalIgnoreCase") } |
        ForEach-Object {
            Write-Host "    stopping the running daemon (pid $($_.ProcessId))" -ForegroundColor DarkGray
            Stop-Process -Id $_.ProcessId -Force -EA SilentlyContinue
        }
    Start-Sleep -Milliseconds 500
}

function Remove-Task {
    if (Get-ScheduledTask -TaskName $TaskName -EA SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "    removed the scheduled task"
    }
}

function Install-Task {
    $javaw = Join-Path $Prefix "app\runtime\bin\javaw.exe"
    if (-not (Test-Path $javaw)) { throw "no bundled runtime at $javaw" }

    Remove-Task

    # javaw, not java: this runs at every logon, and java.exe would put a console
    # window on the screen each time.
    $action = New-ScheduledTaskAction -Execute $javaw `
        -Argument ("--enable-native-access=ALL-UNNAMED " +
                   "`"-Djava.library.path=$Prefix\app\lib\native`" " +
                   "-cp `"$Prefix\app\lib\*`" " +
                   "com.pegasus.bridge.daemon.BridgeDaemon " +
                   "`"--data-root=$Prefix`"") `
        -WorkingDirectory (Join-Path $Prefix "app")

    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"

    # AllowStartIfOnBatteries and DontStopIfGoingOnBatteries are not optional on a
    # laptop: without them the task simply never runs unplugged. ExecutionTimeLimit 0
    # because this is a daemon, not a job that finishes. RestartCount is what buys
    # back the Restart=on-failure the systemd unit has.
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -MultipleInstances IgnoreNew `
        -StartWhenAvailable `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)

    $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Settings $settings -Principal $principal -Description $TaskDesc | Out-Null

    Write-Host "==> scheduled task '$TaskName' registered (starts at logon)"
    Start-ScheduledTask -TaskName $TaskName
}

# ── Main ───────────────────────────────────────────────────────────────────

Write-Host "=== PegasusBridge install ===" -ForegroundColor Cyan
Write-Host "prefix: $Prefix"
Write-Host ""

if ($LinkOnly -and -not $Uninstall) {
    Update-Themes
    Write-Host ""
    Write-Host "data root: $Prefix"
    exit 0
}

if ($Uninstall) {
    Write-Host "==> removing"
    Stop-Daemon
    Remove-Task
    Remove-Item (Join-Path $Prefix "daemon.json") -Force -EA SilentlyContinue
    if (Test-Path (Join-Path $Prefix "app")) { Remove-Item (Join-Path $Prefix "app") -Recurse -Force }
    if (-not $NoThemes) { Invoke-Pointers "remove" }
    Write-Host ""
    Write-Host "done. Data under $Prefix was left alone; delete it by hand if you want it gone."
    exit 0
}

if (-not (Test-Path (Join-Path $Here "runtime\bin\javaw.exe"))) {
    throw "run this from inside the packaged bundle (no runtime\bin\javaw.exe beside install.ps1)"
}

# Refuse to install from the installed copy. A full install wipes <Prefix>\app and
# then copies from $Here — so running the installed install.ps1 deletes the very
# directory it is reading from, and the result is an empty install. Only -LinkOnly
# and -Uninstall are safe from there, and both have already returned by this point.
# install.sh has the same shape and only avoids it by convention; here it is a stop.
$hereFull   = (Resolve-Path $Here).Path.TrimEnd('\')
$prefixFull = [System.IO.Path]::GetFullPath($Prefix).TrimEnd('\')
if ($hereFull.StartsWith($prefixFull, "OrdinalIgnoreCase")) {
    throw ("refusing to install from inside the installation itself ($hereFull).`n" +
           "         Run install.ps1 from the unpacked bundle. From here only`n" +
           "         -LinkOnly and -Uninstall make sense.")
}

Write-Host "==> installing to $Prefix\app"
Stop-Daemon
# The whole app directory is replaced rather than copied over: the jlink runtime
# contains read-only files, so copying onto an existing install fails partway and
# leaves a mixture of two versions. Only app\ goes — the data beside it stays.
$appDir = Join-Path $Prefix "app"
if (Test-Path $appDir) { Remove-Item $appDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $appDir | Out-Null
Copy-Item (Join-Path $Here "*") -Destination $appDir -Recurse -Force

Update-Themes

if ($NoService) {
    Write-Host "==> no service installed. Start it yourself with:"
    Write-Host "    $appDir\pegasus-bridge.cmd"
} else {
    Install-Task
}

Write-Host ""
Write-Host "installed." -ForegroundColor Green
Write-Host "data root:     $Prefix"
Write-Host "endpoint file: $Prefix\daemon.json  (written by the daemon once it is up)"
Write-Host ""
Write-Host "Installed a theme since? Point it at the Bridge without reinstalling:"
Write-Host "    $appDir\install.ps1 -LinkOnly"
