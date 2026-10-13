#!/usr/bin/env bash
# Runs the tests rcheevos has of its own hashing on the copy of rcheevos the
# library is built from, which is upstream's with our patches on it.
#
# Every patch is there to refuse a file that is no valid image, and each says
# it changes nothing a valid file hashes to. The rows of native_repro_test.py
# hold the first half. This holds the second with the files and the hashes
# upstream's authors wrote down: a patch that changes what one of those files
# hashes to fails a test of theirs here. No further than that. Their files
# are small and plain, and a bound of ours drawn a byte too tight, at a
# length none of them has, passes every one: the sector size of a .gdi sheet
# held to eight digits where the patch allows nine does, and held to three,
# which their sheets have four of, fails five tests. The edge of a bound is
# for the rows of native_repro_test.py, with a valid file on one side of it
# and a refused one on the other.
#
#   ./rcheevos_rhash_test.sh [out-dir]
#
# It compiles native/test/rhash_tests.c, a main() for the suites under
# rcheevos/test/rhash, with those suites and with rcheevos' hashing, twice:
# as plain C11, and with the address and undefined-behaviour sanitizers. Then
# it runs both. Each has to exit 0 and end its output with the line upstream's
# framework closes on, "Done. N/N passed", the two numbers the same and above
# zero. The programs go to out-dir, or to a temporary directory that is
# removed when no out-dir is given.
#
# The compiler is $CC, or gcc. RCHEEVOS_DIR names another copy of rcheevos to
# test in place of the repository's, which is how the check is seen to fail:
# on a copy with one line of the hashing changed.
#
# The defines are upstream's and not the library's: nothing is switched off,
# so that the tests of every console run, and the warnings are off, upstream's
# code not being ours to tidy. This is not in native/build.sh, whose text the
# committed library's manifest holds by checksum.
#
# What the sanitizers print is not judged, only how the program ends. The
# tests' own generator of data overflows a signed int twice, in
# test/rhash/data.c, which the sanitizer reports and carries on from: those
# two lines are on stderr in every run.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
rcheevos="${RCHEEVOS_DIR:-$repo/hasher/src/main/cpp/rcheevos}"
driver="$repo/shared/native/test/rhash_tests.c"
cc="${CC:-gcc}"

if [ ! -f "$rcheevos/test/rhash/test_hash.c" ]; then
    echo "error: no rcheevos with its tests at $rcheevos" >&2
    exit 2
fi

if [ $# -ge 1 ]; then
    out="$1"
    mkdir -p "$out" || exit 2
else
    out="$(mktemp -d)" || exit 2
    trap 'rm -rf "$out"' EXIT
fi

sources=("$driver"
         "$rcheevos"/test/rhash/*.c
         "$rcheevos"/src/rhash/*.c
         "$rcheevos/src/rc_compat.c"
         "$rcheevos/src/rc_util.c")
includes=("-I$rcheevos/include" "-I$rcheevos/src" "-I$rcheevos/src/rhash"
          "-I$rcheevos/test" "-I$rcheevos/test/rhash")

failed=0

# run_one <name> <flags...>: compiles the tests as <name> with those flags and
# runs them; says on one line how it went.
run_one() {
    local name="$1"; shift
    local program="$out/$name" log="$out/$name.out" status last

    if ! "$cc" "$@" -o "$program" "${sources[@]}" "${includes[@]}"; then
        echo "FAILED: $name did not compile"
        failed=1
        return
    fi

    "$program" > "$log"
    status=$?
    last="$(tail -n 1 "$log")"

    # "Done. 238/238 passed in 0 seconds": both numbers, to be compared.
    if [[ "$last" =~ ^Done\.\ ([0-9]+)/([0-9]+)\ passed ]]; then
        local passed="${BASH_REMATCH[1]}" run="${BASH_REMATCH[2]}"
        if [ "$status" -eq 0 ] && [ "$run" -gt 0 ] && [ "$passed" -eq "$run" ]; then
            echo "ok: $name, $passed/$run passed"
            return
        fi
    fi

    # A test that fails says which and why as it goes, above the last line.
    # A program that was ended in the middle of a line leaves it open.
    cat "$log"
    echo
    echo "FAILED: $name ended with status $status and the last line \"$last\""
    failed=1
}

run_one rhash_tests -std=gnu11 -O2 -w
run_one rhash_tests_sanitized -std=gnu11 -O1 -g -fno-omit-frame-pointer \
    -fsanitize=address,undefined -w

exit "$failed"
