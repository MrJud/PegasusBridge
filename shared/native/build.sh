#!/usr/bin/env bash
# Builds librahasher for the host platform from the rcheevos sources that the
# Android module already vendors, so both platforms hash from the same C.
#
# Output goes to native/out/ on purpose: anything under a module's build/
# directory is deleted by `gradle clean`, which silently removed the library.
#
# Usage: ./build.sh [output-dir] [--tools=<dir>]     (default: ./out, no tools)
#
# Beside the library it writes <library>.manifest: the SHA-256 of the library
# and of every file it was compiled from, with the flags and the compiler. The
# library in native/out is committed, and tests/native_manifest_check.py reads
# the manifest to tell a library that is older than the sources beside it.
#
# --tools=<dir> also builds test/rahash_cli.c there, the same sources with a
# main() and no JVM, once as the library is built and once with the address and
# undefined-behaviour sanitizers. tests/native_repro_test.py runs the two.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cpp="hasher/src/main/cpp"

outdir="$here/out"
tools=""
for arg in "$@"; do
    case "$arg" in
        --tools=?*) tools="${arg#--tools=}" ;;
        -*)         echo "unknown option: $arg" >&2; exit 2 ;;
        *)          outdir="$arg" ;;
    esac
done

if [[ ! -d "$repo/$cpp/rcheevos" ]]; then
    echo "rcheevos sources not found at $repo/$cpp/rcheevos" >&2
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

# The directories are made, and made absolute, here: everything below runs from
# the top of the repository, so that the compiler is given the same relative
# names in every checkout and the manifest can repeat them as they are.
mkdir -p "$outdir"
outdir="$(cd "$outdir" && pwd)"
if [[ -n "$tools" ]]; then
    mkdir -p "$tools"
    tools="$(cd "$tools" && pwd)"
fi
cd "$repo"

# One list of rcheevos sources, in the Android module's folder beside the
# sources themselves. A line is a path from that folder; `#` starts a comment.
rhash=()
while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line//[[:space:]]/}"
    [[ -z "$line" ]] && continue
    if [[ ! -f "$cpp/$line" ]]; then
        echo "$cpp/rahasher.sources names $line, which is not there" >&2
        exit 1
    fi
    rhash+=("$cpp/$line")
done < "$cpp/rahasher.sources"
if [[ ${#rhash[@]} -eq 0 ]]; then
    echo "$cpp/rahasher.sources names no source" >&2
    exit 1
fi

# -std=gnu11: left to itself gcc 15 and later compile C23, in which sscanf and
# strtol are functions glibc has had only since 2.38, and the library then
# loads on nothing older. -fvisibility=hidden keeps this build's own functions
# out of the symbol table; rcheevos marks its own for export, and jni.map below
# hides those.
cflags=(-std=gnu11 -O2 -fPIC -fvisibility=hidden -DRC_HASH_NO_ENCRYPTED)
rc_includes=(-I"$cpp/rcheevos/include" -I"$cpp/rcheevos/src" -I"$cpp/rcheevos/src/rhash")
# -isystem, so that the JDK's headers are not listed among the library's own.
jni_includes=(-isystem "$JAVA_HOME/include" -isystem "$JAVA_HOME/include/$platform_inc")

# On Linux only, where the linker is the one these are options of.
# -z defs: a function no source defines is an error now. Without it a source
# missing from the list links, the library loads, and the first file of that
# console ends the process from inside the dynamic linker.
# -z now: whatever is still unresolved fails System.load, which is reported,
# and not a call made later.
ldflags=()
link_inputs=()
flags_said="${cflags[*]} -shared"
if [[ "$(uname -s)" == Linux ]]; then
    ldflags=(-Wl,-z,defs -Wl,-z,now "-Wl,--version-script=$cpp/jni.map")
    link_inputs=("$cpp/jni.map")
    flags_said+=" ${ldflags[*]}"
fi

sources=("shared/native/rahasher_jni.c" "${rhash[@]}")

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Every file of the repository the compiler reads for the library: the sources
# and, from the compiler's own list of what they include, the headers. A header
# reached as rhash/../rc_compat.h is written as the file it is.
compiled_from() {
    gcc -MM "${cflags[@]}" "${rc_includes[@]}" "${jni_includes[@]}" "${sources[@]}" \
        | tr ' ' '\n' \
        | sed -e 's/\\$//' -e '/:$/d' -e '/^$/d' -e ':up' -e 's#[^/]*/\.\./##' -e 'tup'
}

echo "building $libname -> $outdir"

# The list is made here, before anything is written and in a command of its
# own, so that a compiler that cannot make it ends the build. Made inside the
# $( ) that puts the manifest together it would be one command among several,
# whose failure stops nothing: the library would be built, and the manifest
# beside it would name no C at all and leave the check nothing to hold against
# the sources. A list without one of the sources is refused for the same
# reason, whatever status the compiler ended with.
if ! deps="$(compiled_from)"; then
    echo "the compiler could not list what the library is compiled from (gcc -MM)" >&2
    exit 1
fi
for source in "${sources[@]}"; do
    if ! grep -qxF -- "$source" <<<"$deps"; then
        echo "the compiler's list of what the library is compiled from lacks $source" >&2
        exit 1
    fi
done

# Linked under another name and moved into place. A process that has the old
# library loaded keeps the file it opened, where a linker writing over it would
# change the code under a running daemon; and a build that fails leaves the old
# library whole. The linker marks what it writes as a program to run, which a
# library is not, and git would record the difference.
gcc "${cflags[@]}" -shared -o "$outdir/$libname.new" \
    "${sources[@]}" \
    "${rc_includes[@]}" "${jni_includes[@]}" \
    ${ldflags[@]+"${ldflags[@]}"}
chmod 644 "$outdir/$libname.new"
mv -f "$outdir/$libname.new" "$outdir/$libname"

# No date and no path of this machine in it: the same sources and the same
# compiler give the same library, and are to give the same manifest.
manifest="${libname%.*}.manifest"
inputs="$(printf '%s\n' "$deps" "shared/native/build.sh" "$cpp/rahasher.sources" \
                ${link_inputs[@]+"${link_inputs[@]}"} | LC_ALL=C sort -u)"
{
    echo "# What $libname beside this file was compiled from. native/build.sh writes"
    echo "# the two together and tests/native_manifest_check.py compares them: a"
    echo "# line that no longer holds is a library to build again."
    echo "library $(sha256 "$outdir/$libname") $libname"
    echo "compiler $(gcc --version | head -n 1)"
    echo "flags $flags_said"
    while IFS= read -r file; do
        echo "input $(sha256 "$file") $file"
    done <<<"$inputs"
} > "$outdir/$manifest.new"
mv -f "$outdir/$manifest.new" "$outdir/$manifest"

echo "ok: $outdir/$libname ($(stat -c%s "$outdir/$libname" 2>/dev/null || stat -f%z "$outdir/$libname") bytes)"

if [[ -n "$tools" ]]; then
    cli=("shared/native/test/rahash_cli.c" "${rhash[@]}")
    echo "building rahash_cli and rahash_cli_sanitized -> $tools"
    gcc "${cflags[@]}" -o "$tools/rahash_cli" "${cli[@]}" "${rc_includes[@]}"
    # -O1 and frame pointers are what the sanitizers ask for to name the line.
    gcc -std=gnu11 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -DRC_HASH_NO_ENCRYPTED \
        -o "$tools/rahash_cli_sanitized" "${cli[@]}" "${rc_includes[@]}"
    echo "ok: $tools/rahash_cli, $tools/rahash_cli_sanitized"
fi
