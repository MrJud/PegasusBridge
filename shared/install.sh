#!/usr/bin/env bash
# Installs the PegasusBridge daemon for the current user and points every theme
# that can use it at the data root.
#
#   ./install.sh [--prefix ~/.local/share/pegasus-bridge] [--no-service]
#                [--on-demand [--idle-time 60s] [--port 38700]]
#                [--theme DIR]... [--pegasus-config DIR]... [--no-themes]
#                [--link-only] [--uninstall]
#
# What it does, all under $HOME and reversible with --uninstall:
#   * copies the self-contained bundle to <prefix>/app
#   * writes the pointer files themes need to find the data root (see below)
#   * installs systemd --user units
#
# Pointing themes at the Bridge
# -----------------------------
# A theme is QML: it cannot expand ~ nor read the environment, so the absolute
# data root has to be written where the theme can reach it by relative path.
# That file is `bridge.json`, and it is written in two places, both of which a
# theme is expected to try:
#
#   <pegasus config>/bridge.json  one pointer shared by every theme, always
#                                 written — this is what makes a plain
#                                 `./install.sh` work, and what covers themes
#                                 installed as a symlink to a working copy
#   <theme>/bridge.json           a copy inside each theme that asks for it
#
# Themes are found by scanning the themes/ directory of every Pegasus config
# directory that exists, and a theme is written to only when its own sources
# name `bridge.json` — a theme that cannot use the Bridge gets no file. Pass
# --theme DIR (repeatable) to point one explicitly; an explicit theme is always
# written to, symlink or not. --no-themes skips all of it.
#
# --link-only rewrites just those pointers: use it after installing a new theme,
# or to re-point one, without touching the daemon.
#
# Two service modes:
#   default      the daemon starts at login and stays up (~200 MB resident)
#   --on-demand  systemd holds the port and starts the daemon on the first
#                connection, stopping it again once idle. Costs nothing while
#                Pegasus is closed; a cold start adds about a quarter second to
#                the first request.
set -euo pipefail

prefix="$HOME/.local/share/pegasus-bridge"
themes=()
extra_configs=()
write_pointers=1
link_only=0
install_service=1
uninstall=0
on_demand=0
idle_time="60s"
public_port=""
DEFAULT_PORT=38700
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --theme)          themes+=("${2%/}"); shift 2 ;;
        --pegasus-config) extra_configs+=("${2%/}"); shift 2 ;;
        --no-themes)      write_pointers=0; shift ;;
        --link-only)      link_only=1; shift ;;
        --prefix)         prefix="${2%/}"; shift 2 ;;
        --no-service)     install_service=0; shift ;;
        --on-demand)      on_demand=1; shift ;;
        --idle-time)      idle_time="$2"; shift 2 ;;
        --port)           public_port="$2"; shift 2 ;;
        --uninstall)      uninstall=1; shift ;;
        -h|--help)        sed -n '2,42p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

unit_dir="$HOME/.config/systemd/user"
unit="$unit_dir/pegasus-bridge.service"
proxy_unit="$unit_dir/pegasus-bridge-proxy.service"
socket_unit="$unit_dir/pegasus-bridge-proxy.socket"

# True when nothing is listening on $1. `ss` is the reliable check; without it,
# fall back to attempting a connection, which at least catches a live server.
port_free() {
    local p="$1"
    if command -v ss >/dev/null 2>&1; then
        ! ss -H -ltn "sport = :$p" 2>/dev/null | grep -q .
    else
        ! (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null
    fi
}

pick_port() {
    local p="$1" tries=0
    while ! port_free "$p"; do
        p=$((p + 1)); tries=$((tries + 1))
        [[ $tries -gt 50 ]] && { echo "no free port near $1" >&2; exit 1; }
    done
    echo "$p"
}

# ── Pointing themes at the data root ───────────────────────────────────────

# Every place Pegasus is known to keep its configuration on Linux, one per line.
# A running Pegasus cannot be asked which one it uses, so each that exists gets
# a pointer: they cost nothing, and one naming a data root that no longer exists
# reads as "no Bridge here", which is the truth.
config_dirs() {
    local d real seen=""
    for d in ${extra_configs[@]+"${extra_configs[@]}"} \
             "${XDG_CONFIG_HOME:-$HOME/.config}/pegasus-frontend" \
             "$HOME/.config/pegasus-frontend" \
             "$HOME/.var/app/org.pegasus_frontend.Pegasus/config/pegasus-frontend"; do
        [[ -d "$d" ]] || continue
        real="$(readlink -f "$d")"
        [[ "$seen" == *"|$real|"* ]] && continue
        seen="$seen|$real|"
        printf '%s\n' "$d"
    done
}

# A theme opts in by naming the pointer in its own sources — ReStory does it in
# components/data/BridgeApi.js. A theme that never reads the file gets none: an
# installer has no business leaving litter in a theme it cannot help.
theme_uses_bridge() {
    grep -rqI --exclude-dir=.git --include='*.qml' --include='*.js' \
        -e 'bridge\.json' "$1" 2>/dev/null
}

write_pointer() {
    mkdir -p "$1"
    cat > "$1/bridge.json" <<EOF
{
  "schemaVersion": 1,
  "dataRoot": "$prefix",
  "app": "$prefix/app/pegasus-bridge"
}
EOF
}

# Only ever removes a pointer that names *this* data root. A second install with
# a different --prefix, or a file someone wrote by hand, is left alone.
remove_pointer() {
    local f="$1/bridge.json"
    [[ -f "$f" ]] || return 0
    grep -q "\"dataRoot\"[[:space:]]*:[[:space:]]*\"$prefix\"" "$f" || return 0
    rm -f "$f"
    echo "    removed $f"
}

# Runs $1 ("write" or "remove") over the shared pointers and every theme that
# uses the Bridge.
walk_pointers() {
    local mode="$1" cfg theme name
    while IFS= read -r cfg; do
        [[ -n "$cfg" ]] || continue
        if [[ "$mode" == write ]]; then
            write_pointer "$cfg"
            echo "==> shared pointer: $cfg/bridge.json"
        else
            remove_pointer "$cfg"
        fi
        [[ -d "$cfg/themes" ]] || continue
        for theme in "$cfg"/themes/*/; do
            theme="${theme%/}"
            [[ -d "$theme" ]] || continue
            theme_uses_bridge "$theme" || continue
            name="$(basename "$theme")"
            if [[ "$mode" != write ]]; then
                remove_pointer "$theme"
                continue
            fi
            # A theme directory is often a symlink to a working copy, and
            # writing through it lands inside that repository — so it gets the
            # shared pointer only. --theme overrides this for a theme that
            # cannot read the shared one.
            if [[ -L "$theme" ]]; then
                echo "    $name: symlink -> $(readlink -f "$theme")"
                echo "         left untouched; it reads the shared pointer above."
                echo "         If it only looks in its own directory, re-run with:"
                echo "           $0 --link-only --theme \"$theme\""
                continue
            fi
            write_pointer "$theme"
            echo "    $name: pointed at $prefix"
        done
    done < <(config_dirs)

    for theme in ${themes[@]+"${themes[@]}"}; do
        if [[ "$mode" != write ]]; then
            remove_pointer "$theme"
            continue
        fi
        [[ -d "$theme" ]] || { echo "theme directory not found: $theme" >&2; exit 1; }
        write_pointer "$theme"
        echo "==> pointed $(basename "$theme") at $prefix"
    done
}

link_themes() {
    [[ $write_pointers -eq 1 ]] || return 0
    # The Bridge can be installed before Pegasus has ever run, in which case
    # there is no config directory yet. Create the standard one so the pointer
    # is already waiting: Pegasus would create the same directory itself.
    [[ -n "$(config_dirs)" ]] || mkdir -p "${XDG_CONFIG_HOME:-$HOME/.config}/pegasus-frontend"
    walk_pointers write
}

if [[ $link_only -eq 1 && $uninstall -eq 0 ]]; then
    link_themes
    echo
    echo "data root: $prefix"
    exit 0
fi

if [[ $uninstall -eq 1 ]]; then
    echo "==> removing"
    systemctl --user disable --now pegasus-bridge-proxy.socket 2>/dev/null || true
    systemctl --user stop pegasus-bridge-proxy.service 2>/dev/null || true
    systemctl --user disable --now pegasus-bridge.service 2>/dev/null || true
    rm -f "$unit" "$proxy_unit" "$socket_unit"
    rm -f "$prefix/daemon.json"
    systemctl --user daemon-reload 2>/dev/null || true
    rm -rf "$prefix/app"
    # `if`, not `&&`: under `set -e` a false test here would end the script with
    # a failure status and no closing message.
    if [[ $write_pointers -eq 1 ]]; then walk_pointers remove; fi
    echo "done. Data under $prefix was left alone; delete it by hand if you want it gone."
    exit 0
fi

[[ -x "$here/pegasus-bridge" ]] || { echo "run this from inside the packaged bundle" >&2; exit 1; }

echo "==> installing to $prefix/app"
# The whole app directory is replaced rather than copied over: the jlink runtime
# contains read-only files, so copying onto an existing install fails partway and
# leaves a mixture of two versions. Only app/ goes — the data beside it stays.
if [[ -d "$prefix/app" ]]; then
    chmod -R u+w "$prefix/app" 2>/dev/null || true
    rm -rf "$prefix/app"
fi
mkdir -p "$prefix/app"
cp -r "$here/." "$prefix/app/"
chmod +x "$prefix/app/pegasus-bridge"

# A theme cannot expand ~ or read environment variables, so the absolute data
# root is written where QML can reach it by relative path. A theme reads this,
# then reads daemon.json from the data root for the live port.
link_themes

if [[ $install_service -eq 1 ]] && command -v systemctl >/dev/null 2>&1; then
    mkdir -p "$unit_dir"
    # Whichever mode is chosen, the other one's units must go: leaving both in
    # place means two things competing to own the same daemon.
    systemctl --user disable --now pegasus-bridge-proxy.socket 2>/dev/null || true
    systemctl --user stop pegasus-bridge-proxy.service 2>/dev/null || true
    systemctl --user disable --now pegasus-bridge.service 2>/dev/null || true
    rm -f "$unit" "$proxy_unit" "$socket_unit"
    # An older install could have left units in 'failed' (a SIGTERM exit of 143
    # counted as one). That state sticks and would be reported as a live error.
    systemctl --user reset-failed pegasus-bridge.service pegasus-bridge-proxy.service \
        2>/dev/null || true

    if [[ $on_demand -eq 1 ]]; then
        # Ships with systemd, but the directory differs between distributions.
        PROXYD=""
        for cand in /usr/lib/systemd/systemd-socket-proxyd \
                    /lib/systemd/systemd-socket-proxyd \
                    /usr/libexec/systemd/systemd-socket-proxyd; do
            [[ -x "$cand" ]] && { PROXYD="$cand"; break; }
        done
        # The daemon signals readiness by running this; without it the proxy
        # would connect before the port is open and the first request of every
        # cold start would come back empty.
        NOTIFY="$(command -v systemd-notify || true)"
        if [[ -z "$PROXYD" || -z "$NOTIFY" ]]; then
            echo "systemd-socket-proxyd or systemd-notify not found;" \
                 "falling back to the always-on service" >&2
            on_demand=0
        fi
    fi

    if [[ $on_demand -eq 1 ]]; then
        pub="$(pick_port "${public_port:-$DEFAULT_PORT}")"
        internal="$(pick_port $((pub + 1)))"
        echo "==> installing on-demand activation (port $pub, idle timeout $idle_time)"

        # systemd owns $pub and starts the proxy on the first connection; the
        # proxy pulls up the daemon behind it on $internal. The daemon cannot
        # take systemd's socket itself — a JVM cannot adopt an inherited
        # listening descriptor — which is exactly what systemd-socket-proxyd is
        # for.
        cat > "$socket_unit" <<EOF
[Unit]
Description=Pegasus Bridge socket (starts the daemon on demand)

[Socket]
ListenStream=127.0.0.1:$pub

[Install]
WantedBy=sockets.target
EOF

        cat > "$proxy_unit" <<EOF
[Unit]
Description=Pegasus Bridge socket proxy
Requires=pegasus-bridge.service
After=pegasus-bridge.service

[Service]
Type=notify
ExecStart=$PROXYD --exit-idle-time=$idle_time 127.0.0.1:$internal
PrivateTmp=no
EOF

        # No [Install]: this is pulled up by the proxy and, with
        # StopWhenUnneeded, released the moment the proxy goes away.
        cat > "$unit" <<EOF
[Unit]
Description=Pegasus Bridge daemon
Documentation=https://github.com/MrJud/PegasusBridge
StopWhenUnneeded=yes

[Service]
# notify, not simple: the daemon says when it is listening. With simple, the
# proxy in front connects the moment the process is forked and the first
# request of a cold start is refused. NotifyAccess=all because the message is
# sent by systemd-notify, not by the JVM itself.
Type=notify
NotifyAccess=all
TimeoutStartSec=60
ExecStart=$prefix/app/pegasus-bridge --data-root=$prefix --port=$internal --advertise-port=$pub
Restart=on-failure
RestartSec=5
# A JVM killed by SIGTERM exits 143, which systemd calls a failure. Left alone
# that marks the unit failed on every ordinary stop — and under on-demand, where
# Restart=on-failure would then fight the idle shutdown, it would bring the
# daemon straight back up.
SuccessExitStatus=143
EOF

        # The endpoint file has to exist before the daemon has ever run: it is
        # how the theme learns which port to knock on, and knocking is what
        # starts the daemon. The daemon rewrites the same values on each start
        # and, in this mode, never deletes it.
        mkdir -p "$prefix"
        cat > "$prefix/daemon.json" <<EOF
{
  "schemaVersion": 1,
  "port": $pub,
  "dataRoot": "$prefix",
  "managed": true
}
EOF

        systemctl --user daemon-reload
        systemctl --user enable --now pegasus-bridge-proxy.socket
        sleep 1
        if systemctl --user is-active --quiet pegasus-bridge-proxy.socket; then
            echo "    listening on 127.0.0.1:$pub, daemon starts on first use"
        else
            echo "    socket failed to start; check: systemctl --user status pegasus-bridge-proxy.socket" >&2
        fi
    else
        echo "==> installing the user service"
        cat > "$unit" <<EOF
[Unit]
Description=Pegasus Bridge daemon
Documentation=https://github.com/MrJud/PegasusBridge
After=default.target

[Service]
Type=simple
ExecStart=$prefix/app/pegasus-bridge --data-root=$prefix
Restart=on-failure
RestartSec=5
# A JVM killed by SIGTERM exits 143, which systemd calls a failure. Left alone
# that marks the unit failed on every ordinary stop — and under on-demand, where
# Restart=on-failure would then fight the idle shutdown, it would bring the
# daemon straight back up.
SuccessExitStatus=143

[Install]
WantedBy=default.target
EOF
        systemctl --user daemon-reload
        systemctl --user enable pegasus-bridge.service
        # restart, not `enable --now`: on an upgrade the service is already running and
        # `--now` leaves the old binary in place, so the new one never takes effect.
        systemctl --user restart pegasus-bridge.service
        sleep 2
        if systemctl --user is-active --quiet pegasus-bridge.service; then
            echo "    running"
        else
            echo "    service failed to start; check: systemctl --user status pegasus-bridge" >&2
        fi
    fi
else
    echo "==> no service installed. Start it yourself with:"
    echo "    $prefix/app/pegasus-bridge --data-root=$prefix"
fi

echo
echo "installed."
echo "data root:     $prefix"
echo "endpoint file: $prefix/daemon.json  (written once the daemon is up)"
echo
echo "Installed a theme since? Point it at the Bridge without reinstalling:"
echo "    $prefix/app/install.sh --link-only"
