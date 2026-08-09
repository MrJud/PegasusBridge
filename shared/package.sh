#!/usr/bin/env bash
# Builds a self-contained PegasusBridge daemon: a trimmed Java runtime, the jars
# and the native hasher, so the machine needs no JRE installed.
#
# Usage: ./package.sh [output-dir]      (default: ./dist/pegasus-bridge)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="${1:-$here/dist/pegasus-bridge}"

# Under Git Bash / MSYS2 everything below works unchanged — jlink, jdeps, tar and
# sha256sum are all there — but two things do have to know the host: the bundle
# needs a launcher Windows can run, and the archive name has to stop claiming to
# be a Linux build.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host_os="windows" ;;
    Darwin)               host_os="macos"   ;;
    *)                    host_os="linux"   ;;
esac

: "${JAVA_HOME:?set JAVA_HOME to a JDK — jlink and jni.h both live there}"
for tool in jlink jdeps; do
    [[ -x "$JAVA_HOME/bin/$tool" ]] || { echo "$tool not found in $JAVA_HOME/bin" >&2; exit 1; }
done

echo "==> building the daemon distribution"
"$JAVA_HOME/bin/java" -cp "$here/../gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain -p "$here" :daemon:installDist --console=plain -q

install_dir="$here/daemon/build/install/daemon"
[[ -d "$install_dir/lib" ]] || { echo "installDist produced nothing at $install_dir" >&2; exit 1; }

echo "==> resolving the JDK modules the jars actually need"
# --ignore-missing-deps: optional dependencies of okhttp and NewPipe are absent
# by design and must not fail the analysis.
modules="$("$JAVA_HOME/bin/jdeps" --multi-release 21 --print-module-deps \
           --ignore-missing-deps "$install_dir"/lib/*.jar)"
# jdk.crypto.ec is needed for TLS but is invisible to static analysis, because
# it is loaded as a security provider at runtime. Without it every HTTPS call
# fails, which is every call this daemon makes.
modules="$modules,jdk.crypto.ec,jdk.crypto.cryptoki"
echo "    $modules"

echo "==> linking the runtime"
rm -rf "$out"
mkdir -p "$out"
"$JAVA_HOME/bin/jlink" \
    --add-modules "$modules" \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$out/runtime"

echo "==> assembling"
cp -r "$install_dir/lib" "$out/lib"

cat > "$out/pegasus-bridge" <<'LAUNCHER'
#!/usr/bin/env bash
# Self-contained launcher: uses the bundled runtime, never a system JRE.
set -euo pipefail
here="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
exec "$here/runtime/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    -Djava.library.path="$here/lib/native" \
    -cp "$here/lib/*" \
    com.pegasus.bridge.daemon.BridgeDaemon "$@"
LAUNCHER
chmod +x "$out/pegasus-bridge"

if [[ "$host_os" == "windows" ]]; then
    # The bash launcher above only runs under Git Bash, which a user installing a
    # release has no reason to have. This one uses java.exe on purpose, console and
    # all: it is the "run it by hand and watch the log" entry point. The Scheduled
    # Task that install.ps1 registers does not go through here — it points straight
    # at runtime\bin\javaw.exe, so nothing flashes a console window at every logon.
    cat > "$out/pegasus-bridge.cmd" <<'LAUNCHER'
@echo off
rem Self-contained launcher: uses the bundled runtime, never a system JRE.
setlocal
set "HERE=%~dp0"
"%HERE%runtime\bin\java.exe" --enable-native-access=ALL-UNNAMED -Djava.library.path="%HERE%lib\native" -cp "%HERE%lib\*" com.pegasus.bridge.daemon.BridgeDaemon %*
exit /b %ERRORLEVEL%
LAUNCHER
    cp "$here/install.ps1" "$out/install.ps1" 2>/dev/null || true
fi

cp "$here/install.sh" "$out/install.sh" 2>/dev/null || true
chmod +x "$out/install.sh" 2>/dev/null || true

# GPLv3 requires the licence to travel with the binary, and this bundle is a
# binary: without it the only licence texts inside are the JDK runtime's own.
cp "$here/../LICENSE" "$out/LICENSE" 2>/dev/null || true

size="$(du -sh "$out" | cut -f1)"
echo
echo "done: $out ($size)"
echo "run with: $out/pegasus-bridge"

# --tarball turns the directory into what a release actually ships: one archive
# with the version and architecture in its name, plus a checksum, so a user can
# tell what they downloaded and whether it arrived intact.
if [[ "${MAKE_TARBALL:-0}" == "1" ]]; then
    version="${BRIDGE_VERSION:-$(git -C "$here/.." describe --tags --always 2>/dev/null || echo dev)}"
    arch="$(uname -m)"
    name="pegasus-bridge-${version}-${host_os}-${arch}"
    parent="$(dirname "$out")"
    staged="$parent/$name"

    echo "==> archiving as $name.tar.gz"
    rm -rf "$staged"
    cp -r "$out" "$staged"
    tar -C "$parent" -czf "$parent/$name.tar.gz" "$name"
    rm -rf "$staged"
    ( cd "$parent" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256" )

    echo "    $parent/$name.tar.gz ($(du -h "$parent/$name.tar.gz" | cut -f1))"
    echo "    $parent/$name.tar.gz.sha256"
fi
