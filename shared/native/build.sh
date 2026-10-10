#!/usr/bin/env bash
# Builds librahasher for the host platform from the rcheevos sources that the
# Android module already vendors and the JNI file beside them, so both
# platforms hash from the same C and are entered the same way.
#
# Output goes to native/out/ on purpose: anything under a module's build/
# directory is deleted by `gradle clean`, which silently removed the library.
#
# Usage: ./build.sh [output-dir] [--tools=<dir>] [--target=windows]
#                                            (default: ./out, no tools, the host)
#
# Beside the library it writes <library>.manifest: the SHA-256 of the library
# and of every file it was compiled from, with the flags and the compiler. The
# library in native/out is committed, and tests/native_manifest_check.py reads
# the manifest to tell a library that is older than the sources beside it.
#
# --tools=<dir> also builds test/rahash_cli.c there, the same sources with a
# main() and no JVM, once as the library is built and once with the address and
# undefined-behaviour sanitizers. tests/native_repro_test.py runs the two.
#
# --target=windows builds rahasher.dll on a system that is not Windows, with
# the mingw-w64 compiler, which is also the one used on Windows itself. That
# shows the library compiles and what it asks of a system, and no more: only
# Windows can load it. On Windows this script runs in Git Bash or MSYS2, and
# the DLL is what it builds there unasked.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cpp="hasher/src/main/cpp"

case "$(uname -s)" in
    Linux)  target="linux"   ;;
    Darwin) target="macos"   ;;
    *)      target="windows" ;;
esac

outdir="$here/out"
tools=""
for arg in "$@"; do
    case "$arg" in
        --tools=?*)       tools="${arg#--tools=}" ;;
        --target=windows) target="windows" ;;
        -*)               echo "unknown option: $arg" >&2; exit 2 ;;
        *)                outdir="$arg" ;;
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

# jni.h is the same file in every JDK; the few lines beside it that differ
# from one system to the next are jni_md.h. For Windows that file is the one
# in native/include/win32: the JDK that is at hand where the library is built
# for another system has only its own, and on Windows too the library is then
# compiled from what is in the repository.
cc="gcc"
case "$target" in
    linux)   libname="librahasher.so"    ; platform_inc="$JAVA_HOME/include/linux"  ;;
    macos)   libname="librahasher.dylib" ; platform_inc="$JAVA_HOME/include/darwin" ;;
    windows) libname="rahasher.dll"      ; platform_inc="shared/native/include/win32"
             # By the name that says what it compiles for, on Windows as well:
             # the gcc first on PATH there may be MSYS2's or Cygwin's own, whose
             # library would need a DLL of theirs to load.
             cc="x86_64-w64-mingw32-gcc" ;;
esac
if ! command -v "$cc" >/dev/null 2>&1; then
    echo "no $cc on PATH: it is the compiler $libname is built with" >&2
    exit 1
fi
# The second of the two test programs is built with the sanitizers, and
# mingw-w64 has neither. A program of that name built without them would be
# taken for one that had found nothing.
if [[ -n "$tools" && "$target" == windows ]]; then
    echo "--tools builds the test programs for Linux and macOS only: mingw-w64 has no sanitizers" >&2
    exit 2
fi

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

# What of rcheevos is left out, which is the same for every system the
# library is built for and for the test programs.
defines=(-DRC_HASH_NO_ENCRYPTED)

# -std=gnu11: left to itself gcc 15 and later compile C23, in which sscanf and
# strtol are functions glibc has had only since 2.38, and the library then
# loads on nothing older. -fvisibility=hidden keeps this build's own functions
# out of the symbol table; rcheevos marks its own for export, and jni.map below
# hides those.
cflags=(-std=gnu11 -O2 -fPIC -fvisibility=hidden "${defines[@]}")
rc_includes=(-I"$cpp/rcheevos/include" -I"$cpp/rcheevos/src" -I"$cpp/rcheevos/src/rhash")
# -isystem, so that the JDK's headers are not listed among the library's own.
jni_includes=(-isystem "$JAVA_HOME/include" -isystem "$platform_inc")

ldflags=()
# What the library is made from and the compiler's list below does not have.
other_inputs=()
case "$target" in
    linux)
        # Where the linker is the one these are options of.
        # -z defs: a function no source defines is an error now. Without it a
        # source missing from the list links, the library loads, and the first
        # file of that console ends the process from inside the dynamic linker.
        # -z now: whatever is still unresolved fails System.load, which is
        # reported, and not a call made later.
        ldflags=(-Wl,-z,defs -Wl,-z,now "-Wl,--version-script=$cpp/jni.map")
        other_inputs=("$cpp/jni.map")
        ;;
    windows)
        # A DLL is another kind of file, and the two flags above that are about
        # an ELF library say nothing to it: all its code can be put anywhere,
        # and it shows only the functions marked for it, which jni_md.h has
        # JNIEXPORT do and nothing of rcheevos does. Its linker refuses a
        # function nobody defines without being asked.
        cflags=(-std=gnu11 -O2 "${defines[@]}")
        # -static and -static-libgcc: what the compiler adds of its own goes
        # into the library. Left out, the DLL can come to need libgcc and
        # libwinpthread beside it, two files no Windows has and no bundle of
        # ours carries, and is then a library that does not load.
        # --no-insert-timestamp: the linker writes the time of the build into
        # a DLL, and the same sources would never give the same file twice.
        # --disable-auto-image-base: nor in two folders. Left to itself the
        # linker works the address a DLL asks to be loaded at out of the name
        # of the file it writes, folders and all; Windows puts a library
        # where it likes whatever it asks.
        ldflags=(-static -static-libgcc -Wl,--no-insert-timestamp -Wl,--disable-auto-image-base)
        # jni.h is found among the JDK's headers, and of what such a header
        # includes in its turn the compiler's list says nothing.
        other_inputs=("$platform_inc/jni_md.h")
        ;;
esac
flags_said="${cflags[*]} -shared"
if [[ ${#ldflags[@]} -gt 0 ]]; then
    flags_said+=" ${ldflags[*]}"
fi

# The file with the functions the JVM calls is beside the sources, where the
# Android build compiles the same one.
sources=("$cpp/rahasher_jni.c" "${rhash[@]}")

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Every file of the repository the compiler reads for the library: the sources
# and, from the compiler's own list of what they include, the headers. A header
# reached as rhash/../rc_compat.h is written as the file it is. A compiler
# that runs on Windows ends its lines as Windows does.
compiled_from() {
    "$cc" -MM "${cflags[@]}" "${rc_includes[@]}" "${jni_includes[@]}" "${sources[@]}" \
        | tr -d '\r' | tr ' ' '\n' \
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
    echo "the compiler could not list what the library is compiled from ($cc -MM)" >&2
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
"$cc" "${cflags[@]}" -shared -o "$outdir/$libname.new" \
    "${sources[@]}" \
    "${rc_includes[@]}" "${jni_includes[@]}" \
    ${ldflags[@]+"${ldflags[@]}"}
chmod 644 "$outdir/$libname.new"
mv -f "$outdir/$libname.new" "$outdir/$libname"

# No date and no path of this machine in it: the same sources and the same
# compiler give the same library, and are to give the same manifest.
manifest="${libname%.*}.manifest"
inputs="$(printf '%s\n' "$deps" "shared/native/build.sh" "$cpp/rahasher.sources" \
                ${other_inputs[@]+"${other_inputs[@]}"} | LC_ALL=C sort -u)"
{
    echo "# What $libname beside this file was compiled from. native/build.sh writes"
    echo "# the two together and tests/native_manifest_check.py compares them: a"
    echo "# line that no longer holds is a library to build again."
    echo "library $(sha256 "$outdir/$libname") $libname"
    echo "compiler $("$cc" --version | head -n 1 | tr -d '\r')"
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
    "$cc" "${cflags[@]}" -o "$tools/rahash_cli" "${cli[@]}" "${rc_includes[@]}"
    # -O1 and frame pointers are what the sanitizers ask for to name the line.
    "$cc" -std=gnu11 -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined "${defines[@]}" \
        -o "$tools/rahash_cli_sanitized" "${cli[@]}" "${rc_includes[@]}"
    echo "ok: $tools/rahash_cli, $tools/rahash_cli_sanitized"
fi
