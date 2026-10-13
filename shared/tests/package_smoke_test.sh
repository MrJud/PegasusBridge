#!/usr/bin/env bash
# Starts the daemon out of a bundle package.sh made, as a user's machine would,
# and holds what only a bundle can get wrong: the trimmed runtime starts, the
# hasher's library is found through the bundle's own folder and loads, the
# daemon answers, turns a browser away and stops in order.
#
#   ./package_smoke_test.sh <pegasus-bridge-VERSION-linux-ARCH.tar.gz | unpacked bundle>
#
# A tarball is first held to the .sha256 package.sh wrote beside it.
#
# Everything happens in a temporary folder: the data root, the home and the
# unpacked bundle. The port is whichever the system hands out, so a daemon
# installed on this machine is neither asked nor stopped. Nothing is asked of
# any game service: the data root is empty, so there are no credentials, and
# /health reads the configuration and nothing else.
set -uo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <tarball | unpacked bundle>" >&2; exit 2; }
given="$1"

failures=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1${2:+  — $2}"; failures=$((failures + 1)); }

sandbox=""
child=""
cleanup() {
    if [[ -n "$child" ]] && kill -0 "$child" 2>/dev/null; then
        kill -KILL "$child" 2>/dev/null
        wait "$child" 2>/dev/null
    fi
    [[ -n "$sandbox" && -d "$sandbox" ]] && rm -rf "$sandbox"
}
trap cleanup EXIT

# Resolved, links and all: the daemon names its library by the path the
# launcher worked out with readlink, and that line is compared below.
sandbox="$(cd "$(mktemp -d -t bridge-package-test.XXXXXX)" && pwd -P)"
mkdir -p "$sandbox/home"

finish() {
    echo
    if [[ $failures -eq 0 ]]; then
        echo "all checks passed"
        exit 0
    fi
    echo "$failures check(s) failed"
    if [[ -s "$sandbox/out.txt" ]]; then
        echo "-- what the daemon wrote"
        cat "$sandbox/out.txt"
    fi
    exit 1
}

# ── 1. the archive is the one its checksum names, and unpacks to one folder ──
if [[ -d "$given" ]]; then
    echo "== an unpacked bundle"
    bundle="$(cd "$given" && pwd -P)"
elif [[ -f "$given" ]]; then
    echo "== the archive"
    # From the archive's own folder: the .sha256 names the file with no path.
    if ( cd "$(dirname "$given")" && sha256sum -c --quiet "$(basename "$given").sha256" ) \
            > "$sandbox/sum.txt" 2>&1; then
        pass "the archive is the one its .sha256 names"
    else
        fail "the archive is the one its .sha256 names" "$(tr '\n' ' ' < "$sandbox/sum.txt")"
    fi
    mkdir "$sandbox/unpacked"
    if tar -xzf "$given" -C "$sandbox/unpacked"; then
        pass "the archive unpacks"
    else
        fail "the archive unpacks"
        finish
    fi
    bundle="$(printf '%s\n' "$sandbox"/unpacked/*)"
    if [[ -d "$bundle" && "$(basename "$bundle").tar.gz" == "$(basename "$given")" ]]; then
        pass "it holds one folder, named as the archive is"
    else
        fail "it holds one folder, named as the archive is" "found: $bundle"
        finish
    fi
else
    echo "neither an archive nor a folder: $given" >&2
    exit 2
fi

if [[ -x "$bundle/pegasus-bridge" ]]; then
    pass "the bundle has its launcher"
else
    fail "the bundle has its launcher" "missing or not executable: $bundle/pegasus-bridge"
    finish
fi

# ── 2. it starts, and says where it is ───────────────────────────────────────
echo "== start"
data="$sandbox/data"

# HOME alone does not move a JVM: user.home is read from the account, so the
# daemon would still see the real home of whoever runs this. The two XDG
# variables would take the default data root elsewhere, and
# PEGASUS_BRIDGE_NATIVE would hand the daemon a library that is not the
# bundle's; NOTIFY_SOCKET would have it report to a systemd it is not under.
# Started from the temporary folder, because one of the places the library is
# looked for is under the working directory.
(
    cd "$sandbox" || exit 1
    exec env -u XDG_DATA_HOME -u XDG_CONFIG_HOME -u PEGASUS_BRIDGE_NATIVE -u NOTIFY_SOCKET \
        HOME="$sandbox/home" \
        JAVA_TOOL_OPTIONS="-Duser.home=$sandbox/home" \
        "$bundle/pegasus-bridge" --data-root="$data" --port=0
) > "$sandbox/out.txt" 2>&1 &
child=$!

# Up to 30 seconds, and no longer than the daemon lives.
waited=0
while [[ ! -f "$data/daemon.json" && $waited -lt 300 ]] && kill -0 "$child" 2>/dev/null; do
    sleep 0.1
    waited=$((waited + 1))
done
if [[ -f "$data/daemon.json" ]]; then
    pass "daemon.json is written"
else
    fail "daemon.json is written" "none at $data/daemon.json after $((waited / 10)) s"
    finish
fi

number() {  # key: the whole number daemon.json has under it
    sed -n "s/^[[:space:]]*\"$1\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$data/daemon.json" | head -n 1
}
port="$(number port)"
pid="$(number pid)"
if [[ -n "$port" && "$port" -gt 0 ]]; then
    pass "it names its port"
else
    fail "it names its port" "$(tr '\n' ' ' < "$data/daemon.json")"
    finish
fi
# The launcher ends in `exec`, so the process started here is the JVM itself.
# Were it not, the signal below would reach a shell and leave the daemon up.
if [[ "$pid" == "$child" ]]; then
    pass "its pid is the process that was started"
else
    fail "its pid is the process that was started" "daemon.json says ${pid:-nothing}, started $child"
fi

# ── 3. the hasher's library loaded, and from the bundle ──────────────────────
echo "== the hasher"
# daemon.json is written before the line that says the daemon is ready, and
# that line is where a missing hasher is named: /health does not show it. So
# the line is waited for, or the search below could run before it is there.
waited=0
while ! grep -q 'ready on 127\.0\.0\.1:' "$sandbox/out.txt" && [[ $waited -lt 100 ]]; do
    sleep 0.1
    waited=$((waited + 1))
done
if grep -q "ready on 127\.0\.0\.1:$port," "$sandbox/out.txt"; then
    pass "it says it is ready on that port"
else
    fail "it says it is ready on that port"
fi
if grep -rqs 'no ROM hasher' "$sandbox/out.txt" "$data"; then
    fail "it has a ROM hasher" "$(grep -rhs 'no ROM hasher' "$sandbox/out.txt" "$data" | head -n 1)"
else
    pass "it has a ROM hasher"
fi
if grep -qF "native hasher loaded from $bundle/lib/native/" "$sandbox/out.txt"; then
    pass "the library it loaded is the bundle's own"
else
    fail "the library it loaded is the bundle's own" \
         "$(grep -h 'native hasher' "$sandbox/out.txt" | head -n 1)"
fi

# ── 4. it answers, and turns a browser away ──────────────────────────────────
echo "== requests"
ask() {  # extra curl arguments; prints the status, leaves the body in body.txt
    curl -sS -m 5 --noproxy '*' -o "$sandbox/body.txt" -w '%{http_code}' "$@" \
        "http://127.0.0.1:$port/health" 2> "$sandbox/curl.txt"
}

status="$(ask)"
if [[ "$status" == 200 ]]; then
    pass "/health answers 200"
else
    fail "/health answers 200" "got ${status:-nothing} $(tr '\n' ' ' < "$sandbox/curl.txt")"
fi
if grep -q '"status"[[:space:]]*:[[:space:]]*"ok"' "$sandbox/body.txt" 2>/dev/null; then
    pass "/health says ok"
else
    fail "/health says ok" "$(head -c 300 "$sandbox/body.txt" 2>/dev/null)"
fi
if grep -qF "\"dataRoot\":\"$data\"" "$sandbox/body.txt" 2>/dev/null; then
    pass "/health names the data root it was given"
else
    fail "/health names the data root it was given" "$(head -c 300 "$sandbox/body.txt" 2>/dev/null)"
fi

# What a page open in a browser sends with a request of its own making, and
# what a request sent to this port under another name carries.
status="$(ask -H 'Origin: https://attacker.invalid' -H 'Sec-Fetch-Site: cross-site')"
if [[ "$status" == 403 ]]; then
    pass "a request from a web page is refused"
else
    fail "a request from a web page is refused" "got ${status:-nothing}"
fi
status="$(ask -H 'Host: attacker.invalid')"
if [[ "$status" == 403 ]]; then
    pass "a request for another host is refused"
else
    fail "a request for another host is refused" "got ${status:-nothing}"
fi

# ── 5. it stops in order ─────────────────────────────────────────────────────
echo "== stop"
kill -TERM "$child" 2>/dev/null
waited=0
while kill -0 "$child" 2>/dev/null && [[ $waited -lt 150 ]]; do
    sleep 0.1
    waited=$((waited + 1))
done
if kill -0 "$child" 2>/dev/null; then
    fail "it ends within 15 s of SIGTERM"
else
    wait "$child"
    ended=$?
    child=""
    # 143 is what a JVM ends with when a signal stopped it and its shutdown
    # hooks ran to their end.
    if [[ $ended -eq 0 || $ended -eq 143 ]]; then
        pass "it ends on SIGTERM"
    else
        fail "it ends on SIGTERM" "exit status $ended"
    fi
    if [[ -e "$data/daemon.json" ]]; then
        fail "it takes daemon.json away as it goes"
    else
        pass "it takes daemon.json away as it goes"
    fi
fi

finish
