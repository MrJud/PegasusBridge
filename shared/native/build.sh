#!/usr/bin/env bash
# Builds librahasher for the host platform from the rcheevos sources that the
# Android module already vendors, so both platforms hash from the same C.
#
# Output goes to native/out/ on purpose: anything under a module's build/
# directory is deleted by `gradle clean`, which silently removed the library.
#
# Usage: ./build.sh [output-dir]     (default: ./out)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
rcheevos="$here/../../hasher/src/main/cpp/rcheevos"
outdir="${1:-$here/out}"

if [[ ! -d "$rcheevos" ]]; then
    echo "rcheevos sources not found at $rcheevos" >&2
    exit 1
fi

: "${JAVA_HOME:?set JAVA_HOME to a JDK (the default java may be a JRE without headers)}"
if [[ ! -f "$JAVA_HOME/include/jni.h" ]]; then
    echo "no jni.h under $JAVA_HOME/include — that is a JRE, not a JDK" >&2
    exit 1
fi

case "$(uname -s)" in
    Linux)  libname="librahasher.so"    ; platform_inc="linux"  ;;
    Darwin) libname="librahasher.dylib" ; platform_inc="darwin" ;;
    *)      libname="rahasher.dll"      ; platform_inc="win32"  ;;
esac

mkdir -p "$outdir"

# hash_encrypted.c is excluded, matching the Android CMake build.
sources=(
    "$here/rahasher_jni.c"
    "$rcheevos/src/rhash/cdreader.c"
    "$rcheevos/src/rhash/hash.c"
    "$rcheevos/src/rhash/hash_disc.c"
    "$rcheevos/src/rhash/hash_rom.c"
    "$rcheevos/src/rhash/hash_zip.c"
    "$rcheevos/src/rhash/md5.c"
    "$rcheevos/src/rhash/aes.c"
    "$rcheevos/src/rc_compat.c"
    "$rcheevos/src/rc_util.c"
)

echo "building $libname -> $outdir"
gcc -O2 -fPIC -shared -o "$outdir/$libname" \
    "${sources[@]}" \
    -I"$rcheevos/include" -I"$rcheevos/src" -I"$rcheevos/src/rhash" \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$platform_inc" \
    -DRC_HASH_NO_ENCRYPTED

echo "ok: $outdir/$libname ($(stat -c%s "$outdir/$libname" 2>/dev/null || stat -f%z "$outdir/$libname") bytes)"
