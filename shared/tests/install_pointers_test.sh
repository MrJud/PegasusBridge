#!/usr/bin/env bash
# Proves install.sh points themes at the data root in every layout that occurs
# in the wild — not just the one on the maintainer's machine.
#
#   ./install_pointers_test.sh
#
# Everything happens inside a temporary HOME with a stub `systemctl` on PATH, so
# the real user units and the real Pegasus configuration are never touched.
set -uo pipefail

script_dir="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
installer="$script_dir/../install.sh"
[[ -f "$installer" ]] || { echo "install.sh not found next to the test" >&2; exit 1; }

failures=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1${2:+  — $2}"; failures=$((failures + 1)); }

points_at() {  # file, expected dataRoot
    [[ -f "$1" ]] || return 1
    grep -q "\"dataRoot\"[[:space:]]*:[[:space:]]*\"$2\"" "$1"
}
assert_points()  { if points_at "$1" "$2"; then pass "$3"; else fail "$3" "missing or wrong dataRoot: $1"; fi; }
assert_absent()  { if [[ -e "$1" ]]; then fail "$2" "should not exist: $1"; else pass "$2"; fi; }
assert_present() { if [[ -e "$1" ]]; then pass "$2"; else fail "$2" "missing: $1"; fi; }

sandbox=""
cleanup() { [[ -n "$sandbox" && -d "$sandbox" ]] && chmod -R u+w "$sandbox" 2>/dev/null; rm -rf "$sandbox"; }
trap cleanup EXIT

# A theme that reads the pointer, i.e. one the installer should serve. The
# marker is the same one the installer greps for: the theme's own sources
# naming bridge.json.
make_theme() {  # dir, uses_bridge(0|1)
    mkdir -p "$1/components/data"
    printf 'name: %s\n' "$(basename "$1")" > "$1/theme.cfg"
    printf 'import QtQuick 2.15\nItem {}\n' > "$1/theme.qml"
    if [[ "$2" == 1 ]]; then
        printf 'var POINTERS = ["../../bridge.json", "../../../../bridge.json"];\n' \
            > "$1/components/data/BridgeApi.js"
    else
        printf 'var x = 1;\n' > "$1/components/data/Other.js"
    fi
}

new_sandbox() {
    cleanup
    sandbox="$(mktemp -d "${TMPDIR:-/tmp}/bridge-install-test.XXXXXX")"

    # A stub systemctl: --uninstall calls it unconditionally, and a real call
    # here would disable the maintainer's live daemon.
    mkdir -p "$sandbox/bin"
    printf '#!/bin/sh\nexit 0\n' > "$sandbox/bin/systemctl"
    chmod +x "$sandbox/bin/systemctl"

    # A bundle just real enough for the installer's own guard.
    mkdir -p "$sandbox/bundle"
    printf '#!/bin/sh\necho fake daemon\n' > "$sandbox/bundle/pegasus-bridge"
    chmod +x "$sandbox/bundle/pegasus-bridge"
    cp "$installer" "$sandbox/bundle/install.sh"
    chmod +x "$sandbox/bundle/install.sh"

    prefix="$sandbox/data"
}

run_installer() {
    env -i HOME="$sandbox/home" \
           XDG_CONFIG_HOME="$sandbox/home/.config" \
           PATH="$sandbox/bin:/usr/bin:/bin" \
           TERM=dumb \
        bash "$sandbox/bundle/install.sh" --prefix "$prefix" --no-service "$@" \
        > "$sandbox/out.txt" 2>&1
    local rc=$?
    [[ $rc -eq 0 ]] || { echo "--- installer failed (rc=$rc) ---"; cat "$sandbox/out.txt"; }
    return $rc
}

# ── 1. the mixed layout: plain theme, symlinked theme, unrelated theme ──────
echo "== plain, symlinked and non-Bridge themes side by side"
new_sandbox
cfg="$sandbox/home/.config/pegasus-frontend"
mkdir -p "$cfg/themes"
make_theme "$cfg/themes/PlainTheme" 1
make_theme "$cfg/themes/NoBridge"   0
# A working copy elsewhere, with a git repository in it, symlinked into place.
make_theme "$sandbox/work/WorkCopy" 1
mkdir -p "$sandbox/work/WorkCopy/.git"
ln -s "$sandbox/work/WorkCopy" "$cfg/themes/WorkCopy"

if run_installer; then
    assert_points "$cfg/bridge.json" "$prefix"            "shared pointer written in the config dir"
    assert_points "$cfg/themes/PlainTheme/bridge.json" "$prefix" "plain theme pointed at the data root"
    assert_absent "$sandbox/work/WorkCopy/bridge.json"    "symlinked theme: nothing written inside the repo"
    assert_absent "$cfg/themes/NoBridge/bridge.json"      "theme that never reads the pointer left alone"
    grep -q "WorkCopy: symlink" "$sandbox/out.txt" \
        && pass "the symlink is reported, not silently skipped" \
        || fail "the symlink is reported, not silently skipped"
fi

# ── 2. no --theme at all is enough: that is the whole bug ──────────────────
echo "== a bare ./install.sh leaves a usable pointer"
if [[ -f "$cfg/bridge.json" && -f "$cfg/themes/PlainTheme/bridge.json" ]]; then
    pass "no --theme needed"
else
    fail "no --theme needed" "the bare run left nothing behind"
fi

# ── 3. two config directories, several themes ──────────────────────────────
echo "== two Pegasus config directories, two themes each"
new_sandbox
cfg="$sandbox/home/.config/pegasus-frontend"
flat="$sandbox/home/.var/app/org.pegasus_frontend.Pegasus/config/pegasus-frontend"
mkdir -p "$cfg/themes" "$flat/themes"
make_theme "$cfg/themes/One"   1
make_theme "$cfg/themes/Two"   1
make_theme "$flat/themes/Three" 1
make_theme "$flat/themes/Four"  0

if run_installer; then
    assert_points "$cfg/bridge.json"            "$prefix" "config dir pointer"
    assert_points "$flat/bridge.json"           "$prefix" "flatpak config dir pointer"
    assert_points "$cfg/themes/One/bridge.json"   "$prefix" "theme One"
    assert_points "$cfg/themes/Two/bridge.json"   "$prefix" "theme Two"
    assert_points "$flat/themes/Three/bridge.json" "$prefix" "theme Three"
    assert_absent "$flat/themes/Four/bridge.json"          "theme Four (no Bridge) left alone"
fi

# ── 4. re-pointing later, without reinstalling the daemon ──────────────────
echo "== --link-only picks up a theme installed after the Bridge"
make_theme "$cfg/themes/Later" 1
if run_installer --link-only; then
    assert_points "$cfg/themes/Later/bridge.json" "$prefix" "theme installed later is pointed"
    assert_present "$prefix/app/pegasus-bridge" "--link-only left the installed daemon in place"
fi

echo "== --link-only --theme overrides the symlink rule"
new_sandbox
cfg="$sandbox/home/.config/pegasus-frontend"
mkdir -p "$cfg/themes"
make_theme "$sandbox/work/Linked" 1
ln -s "$sandbox/work/Linked" "$cfg/themes/Linked"
if run_installer; then
    assert_absent "$sandbox/work/Linked/bridge.json" "still nothing inside the repo by default"
fi
if run_installer --link-only --theme "$cfg/themes/Linked"; then
    assert_points "$sandbox/work/Linked/bridge.json" "$prefix" "an explicit --theme is written anyway"
fi

# ── 5. the Bridge installed before Pegasus has ever run ────────────────────
echo "== no Pegasus config directory yet"
new_sandbox
mkdir -p "$sandbox/home"
if run_installer; then
    assert_points "$sandbox/home/.config/pegasus-frontend/bridge.json" "$prefix" \
        "the standard config dir is created and pointed"
fi

# ── 6. a second data root, and hand-written files, are not stolen ──────────
echo "== uninstall removes our pointers and only ours"
new_sandbox
cfg="$sandbox/home/.config/pegasus-frontend"
mkdir -p "$cfg/themes"
make_theme "$cfg/themes/Mine"    1
make_theme "$cfg/themes/Someone" 1
run_installer >/dev/null
printf '{\n  "dataRoot": "/opt/other-bridge"\n}\n' > "$cfg/themes/Someone/bridge.json"
if run_installer --uninstall; then
    assert_absent "$cfg/themes/Mine/bridge.json" "our theme pointer removed"
    assert_absent "$cfg/bridge.json"             "shared pointer removed"
    assert_points "$cfg/themes/Someone/bridge.json" "/opt/other-bridge" \
        "a pointer to another data root survives"
    assert_present "$sandbox/data" "the data root itself is left in place"
fi

# ── 7. --no-themes writes nothing ──────────────────────────────────────────
echo "== --no-themes"
new_sandbox
cfg="$sandbox/home/.config/pegasus-frontend"
mkdir -p "$cfg/themes"
make_theme "$cfg/themes/Untouched" 1
if run_installer --no-themes; then
    assert_absent "$cfg/bridge.json"                    "no shared pointer"
    assert_absent "$cfg/themes/Untouched/bridge.json"   "no theme pointer"
fi

echo
if [[ $failures -eq 0 ]]; then
    echo "all checks passed"
else
    echo "$failures check(s) failed"
fi
exit $((failures > 0))
