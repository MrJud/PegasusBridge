#!/usr/bin/env bash
# Installs the PegasusBridge daemon for the current user and points a theme at it.
#
#   ./install.sh --theme /path/to/themes/ReStory [--prefix ~/.local/share/pegasus-bridge]
#                [--no-service] [--on-demand [--idle-time 60s] [--port 38700]]
#
# What it does, all under $HOME and reversible with --uninstall:
#   * copies the self-contained bundle to <prefix>/app
#   * writes <theme>/bridge.json so the theme can find the data root
#   * installs systemd --user units
#
# Two service modes:
#   default      the daemon starts at login and stays up (~200 MB resident)
#   --on-demand  systemd holds the port and starts the daemon on the first
#                connection, stopping it again once idle. Costs nothing while
#                Pegasus is closed; a cold start adds about a quarter second to
#                the first request.
set -euo pipefail

prefix="$HOME/.local/share/pegasus-bridge"
theme=""
install_service=1
uninstall=0
on_demand=0
idle_time="60s"
public_port=""
DEFAULT_PORT=38700
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --theme)      theme="$2"; shift 2 ;;
        --prefix)     prefix="$2"; shift 2 ;;
        --no-service) install_service=0; shift ;;
        --on-demand)  on_demand=1; shift ;;
        --idle-time)  idle_time="$2"; shift 2 ;;
        --port)       public_port="$2"; shift 2 ;;
        --uninstall)  uninstall=1; shift ;;
        -h|--help)    sed -n '2,20p' "$0"; exit 0 ;;
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

if [[ $uninstall -eq 1 ]]; then
    echo "==> removing"
    systemctl --user disable --now pegasus-bridge-proxy.socket 2>/dev/null || true
    systemctl --user stop pegasus-bridge-proxy.service 2>/dev/null || true
    systemctl --user disable --now pegasus-bridge.service 2>/dev/null || true
    rm -f "$unit" "$proxy_unit" "$socket_unit"
    rm -f "$prefix/daemon.json"
    systemctl --user daemon-reload 2>/dev/null || true
    rm -rf "$prefix/app"
    [[ -n "$theme" ]] && rm -f "$theme/bridge.json"
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

# The theme cannot expand ~ or read environment variables, so the absolute data
# root is written where it can find it: next to its own theme.qml. BridgeApi.js
# reads this, then reads daemon.json from the data root for the live port.
if [[ -n "$theme" ]]; then
    [[ -d "$theme" ]] || { echo "theme directory not found: $theme" >&2; exit 1; }
    cat > "$theme/bridge.json" <<EOF
{
  "dataRoot": "$prefix",
  "app": "$prefix/app/pegasus-bridge"
}
EOF
    echo "==> pointed $(basename "$theme") at $prefix"
fi

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
[[ -n "$theme" ]] && echo "theme pointer: $theme/bridge.json"
echo "data root:     $prefix"
echo "endpoint file: $prefix/daemon.json  (written once the daemon is up)"
