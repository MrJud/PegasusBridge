#!/usr/bin/env bash
# Checks two things a built librahasher.so tells of itself, and that no test
# of its hashes would notice until somebody else's machine did:
#
#   - the newest glibc it asks for. That is the oldest system it loads on,
#     and it is set by how the library was compiled, not by what the sources
#     call: the same C built as C23 asks for 2.38 where C11 asks for 2.14.
#   - the functions it lets other code see, which must be the ones the JVM
#     looks up and no more. Everything else it exports is a name some other
#     library in the same process may have too.
#
# And of a rahasher.dll the same two, as a DLL tells them: the other DLLs it
# needs, which must all be ones Windows has of its own, and its exports. And
# one thing more, which only a DLL can get wrong: that it calls none of the
# C library's "secure" functions that put text together.
#
# And of either, that it does not call the function behind assert(). And of
# a librahasher.so, that none of its variables is a thread's own.
#
#   ./native_lib_check.sh <librahasher.so | rahasher.dll>
#
# It reads the dynamic section and the dynamic symbol table of an ELF file, or
# the import and export tables of a DLL, with objdump: on Linux for either, a
# DLL built there for Windows being a file like another, and on Windows, in
# Git Bash, for a DLL.
set -uo pipefail

# The floor: no build of the library may need a glibc newer than this. It is
# what the build of native/build.sh needs today, for memcpy; raising it is a
# decision about which systems the daemon runs on, to be taken here.
glibc_floor="2.14"

# Exactly these, one to a line.
expected_exports="Java_com_pegasus_bridge_hasher_RcheevosNative_hashForConsole
Java_com_pegasus_bridge_hasher_RcheevosNative_version"

# What a DLL may need beside itself: the kernel's, and the C library, under
# the name it has in every Windows (msvcrt) or the names of the one that came
# with Windows 10 (api-ms-win-crt-*). Anything else is a file that has to
# travel with the DLL, and nothing puts one in a bundle. Matched in either
# case, as Windows takes the names.
system_dlls='^(kernel32|msvcrt|api-ms-win-crt-[a-z0-9-]+)\.dll$'

# What a DLL may not call: sprintf_s, vsprintf_s and their kin, by the name
# the C library has them under. Handed text that does not fit its buffer, one
# of these does not cut it short. It calls that an invalid parameter, and a
# process that has set no handler for one, which the JVM has not, is ended
# there. rcheevos put its messages together with vsprintf_s wherever the
# compiler said it had it, and mingw-w64 always says so: a path of some
# thousand bytes to a track that is not there was the end of the daemon.
# Local patch 0010 has it use vsnprintf, and this is what holds the patch: no
# file can show the difference on a system that is not Windows.
#
# strcpy_s ends the process the same way and is not here, and the DLL calls
# it: four times in rcheevos' parser of a .gdi sheet, each from a buffer the
# parser filled and ended itself into one of the same size, so that no call
# has anything to refuse. CONTEXT.md says so at length, and what holds it is
# the rows of sheets at those bounds in NativeCrashReproTest and in
# native_repro_test.py.
forbidden_calls='printf_s$'

# What neither kind of library may call: the function assert() ends the
# process through, by the names the C libraries have it under (glibc's,
# Android's, and the two of Windows). The decompressors a .chd is unpacked by
# are full of assert(), and a file made to fail one would end the daemon or
# the app where the decompressor's own error ends in a reason. NDEBUG takes
# them out, in build.sh and in CMakeLists.txt; a build that has lost it is
# the same library to every test of its hashes, and is known by this alone.
# A DLL has a second way to come by one, which no flag of ours takes out: see
# what is said of thread-local storage below.
assert_calls='^(__assert_fail|__assert_perror_fail|__assert|__assert2|_assert|_wassert)$'

if [[ $# -ne 1 ]]; then
    echo "usage: native_lib_check.sh <librahasher.so | rahasher.dll>" >&2
    exit 2
fi
library="$1"
if [[ ! -f "$library" ]]; then
    echo "native_lib_check: no library at $library" >&2
    exit 2
fi

# A DLL begins with the letters MZ, whatever it is called. The objdump that
# comes with the compiler for Windows reads one wherever it runs; the system's
# own does on most systems, and says so when it does not.
objdump="objdump"
dll=0
if [[ "$(head -c 2 "$library" | tr -d '\0')" == MZ ]]; then
    dll=1
    if command -v x86_64-w64-mingw32-objdump >/dev/null 2>&1; then
        objdump="x86_64-w64-mingw32-objdump"
    fi
fi

# LC_ALL=C: what objdump prints is matched below by its English headings.
if ! headers="$(LC_ALL=C "$objdump" -p "$library" 2>&1)"; then
    echo "native_lib_check: $objdump cannot read $library: $headers" >&2
    exit 2
fi
headers="$(tr -d '\r' <<<"$headers")"

failures=0

# What both kinds of library are held to at the end: the names it calls in
# other libraries, one to a line, and the names it shows.
asserts_nothing() {
    local asserting
    asserting="$(grep -E "$assert_calls" <<<"$1")"
    if [[ -n "$asserting" ]]; then
        echo "  FAIL calls what assert() ends the process through: it was not built with NDEBUG,"
        echo "       or something the compiler added of its own asserts:"
        sed 's/^/         /' <<<"$asserting"
        failures=$((failures + 1))
    else
        echo "  ok   has no assert() left in it"
    fi
}

exports_are() {
    local exports="$1"
    if [[ "$exports" == "$(LC_ALL=C sort <<<"$expected_exports")" ]]; then
        echo "  ok   exports $(wc -l <<<"$exports" | tr -d ' ') function(s) and nothing else:"
        sed 's/^/         /' <<<"$exports"
    else
        echo "  FAIL exports $(grep -c . <<<"$exports") symbols where these were expected:"
        sed 's/^/         expected  /' <<<"$expected_exports"
        sed 's/^/         exported  /' <<<"$exports" | head -n 20
        failures=$((failures + 1))
    fi
}

verdict() {
    if [[ $failures -gt 0 ]]; then
        echo "native_lib_check: $library is not built as native/build.sh builds it"
        exit 1
    fi
    echo "native_lib_check: $library is as expected"
    exit 0
}

if [[ $dll -eq 1 ]]; then
    # A line for every DLL this one cannot be loaded without.
    needs="$(sed -n 's/^[[:space:]]*DLL Name:[[:space:]]*//p' <<<"$headers" | LC_ALL=C sort -u)"
    strangers="$(grep -Eiv "$system_dlls" <<<"$needs")"
    if [[ -z "$needs" ]]; then
        echo "  FAIL no other DLL is asked for, not the C library either: is this the library?"
        failures=$((failures + 1))
    elif [[ -n "$strangers" ]]; then
        echo "  FAIL needs $(grep -c . <<<"$strangers") DLL(s) that Windows does not have of its own:"
        sed 's/^/         /' <<<"$strangers"
        failures=$((failures + 1))
    else
        echo "  ok   needs $(grep -c . <<<"$needs") DLL(s), all of them Windows' own:"
        sed 's/^/         /' <<<"$needs"
    fi

    # The names it calls in those DLLs: under each "DLL Name:" a table of
    # lines whose last word is the name.
    calls="$(awk '/^[ \t]*DLL Name:/ { on = 1; next }
                  on && /^[ \t]*$/     { on = 0 }
                  on && NF >= 3        { print $NF }' <<<"$headers" | LC_ALL=C sort -u)"
    unsafe="$(grep -E "$forbidden_calls" <<<"$calls")"
    if [[ -z "$calls" ]]; then
        echo "  FAIL no function of another DLL is called: the table of imports was not read"
        failures=$((failures + 1))
    elif [[ -n "$unsafe" ]]; then
        echo "  FAIL calls $(grep -c . <<<"$unsafe") function(s) that end the process on text too long for its buffer:"
        sed 's/^/         /' <<<"$unsafe"
        failures=$((failures + 1))
    else
        echo "  ok   calls $(grep -c . <<<"$calls") function(s) of those, none that ends the process on text too long"
    fi
    asserts_nothing "$calls"

    # Under this heading, a line for every name the DLL shows, the name
    # last, up to the first empty line.
    exports_are "$(awk '/^\[Ordinal\/Name Pointer\] Table/ { on = 1; next }
                        on && /^[ \t]*\[/ { print $NF; next }
                        on && /^[ \t]*$/  { on = 0 }' <<<"$headers" | LC_ALL=C sort)"
    verdict
fi

if ! symbols="$(LC_ALL=C objdump -T "$library" 2>&1)"; then
    echo "native_lib_check: objdump cannot read $library: $symbols" >&2
    exit 2
fi

# What the loader checks before it runs anything is the library's list of
# version references, and that is what is read here: the last word of each line
# under that heading. The versions written beside the symbols are not all of
# it. A library linked with -z pack-relative-relocs asks for GLIBC_ABI_DT_RELR,
# which no symbol carries and which glibc has had since 2.36; some toolchains
# link that way unasked.
needs="$(awk '/^Version References:/ { on = 1; next }
              /^[^ \t]/              { on = 0 }
              on && NF == 4 && $4 ~ /^GLIBC_/ { print $4 }' <<<"$headers" | sort -u)"
highest=""
because=""
unknown=""
while IFS= read -r need; do
    [[ -z "$need" ]] && continue
    case "$need" in
        GLIBC_ABI_DT_RELR) version="2.36" ;;
        *)  version="${need#GLIBC_}"
            if [[ ! "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
                unknown+="${unknown:+ }$need"
                continue
            fi ;;
    esac
    if [[ -z "$highest" || ( "$version" != "$highest" &&
          "$(printf '%s\n%s\n' "$version" "$highest" | sort -V | tail -n 1)" == "$version" ) ]]; then
        highest="$version"
        because="$need"
    fi
done <<<"$needs"

if [[ -n "$unknown" ]]; then
    # Not a release of glibc, so there is nothing to hold against the floor.
    echo "  FAIL asks glibc for $unknown, which is no version this check knows the age of"
    failures=$((failures + 1))
fi
if [[ -z "$highest" ]]; then
    if [[ -z "$unknown" ]]; then
        echo "  FAIL no version of glibc is asked for: is this the library?"
        failures=$((failures + 1))
    fi
elif [[ "$(printf '%s\n%s\n' "$highest" "$glibc_floor" | sort -V | tail -n 1)" != "$glibc_floor" ]]; then
    echo "  FAIL needs glibc $highest, and the floor is $glibc_floor:"
    if [[ "$because" == GLIBC_ABI_DT_RELR ]]; then
        echo "         $because: its relocations are packed (-z pack-relative-relocs)"
    else
        grep -F "($because)" <<<"$symbols" | sed 's/^/         /'
    fi
    failures=$((failures + 1))
else
    echo "  ok   needs glibc $highest (the floor is $glibc_floor)"
fi

# No variable of the library may be a thread's own (_Thread_local, __thread).
# Here that costs nothing: the system keeps such variables, and the library
# has a segment of them, which is what is looked for. The same sources are the
# DLL, and a mingw-w64 gcc built without thread-local storage of its own, as
# the one that builds the DLL in CI is, makes such a variable out of libgcc
# and its threading library, winpthreads. Both are linked into the DLL, and
# winpthreads calls assert(), which NDEBUG in our flags does not reach: the
# DLL is refused above for _assert, on that compiler alone and with nothing
# that says where it came from. The library had one such variable, the
# container pb_wbfs.c was reading. This says it on every system, of the
# library the DLL's sources were just compiled into.
if grep -Eq '^[[:space:]]+TLS[[:space:]]+off[[:space:]]' <<<"$headers"; then
    echo "  FAIL has thread-local storage: some variable in it is a thread's own, and the DLL built"
    echo "       from the same sources gets assert() with it where the compiler emulates one:"
    awk '$2 ~ /^[lg]$/ && /\.t(bss|data)/ { print "         " $NF }' \
        <<<"$(LC_ALL=C objdump -t "$library" 2>/dev/null)"
    failures=$((failures + 1))
else
    echo "  ok   has no thread-local storage"
fi

# A line of the table that is *UND* is a name the library calls in another;
# one that begins with an address and is not *UND* is a symbol the library
# defines and shows. The name is the last word of either.
asserts_nothing "$(awk '/\*UND\*/ { print $NF }' <<<"$symbols" | LC_ALL=C sort -u)"
exports_are "$(awk '/^[0-9a-f]+ / && $0 !~ /\*UND\*/ { print $NF }' <<<"$symbols" | LC_ALL=C sort)"
verdict
