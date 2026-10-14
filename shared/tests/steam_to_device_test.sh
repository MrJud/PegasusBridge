#!/usr/bin/env bash
# Holds steam-to-device.sh to what it says of itself, on a device that is a
# folder: the merge, the rename, what it removes, what it refuses, and that the
# key is seen by nobody.
#
#   ./steam_to_device_test.sh
#
# No device and no adb are needed, and none is spoken to. The script is run
# with a PATH that holds two programs and nothing else, both written here:
#
#   adb      plays a device. `adb shell CMD` runs CMD on this machine with
#            /sdcard/PegasusData turned into a folder of the sandbox, so the
#            script's own commands do to that folder what they would do to a
#            device. Anything but `shell` it refuses.
#   python3  hands on to the real one.
#
# Each writes down the arguments and the environment it was started with. Those
# are every process the script starts: with nothing else on its PATH, another
# program would be a "command not found". So the key in no recorded line is
# the key in no process's arguments and in no environment, and not only in
# adb's.
#
# HOME is a folder of the sandbox too, so the default place of the PC's
# credentials.json is never the real one.
set -uo pipefail

script_dir="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
script="$script_dir/../steam-to-device.sh"
[[ -f "$script" ]] || { echo "steam-to-device.sh not found next to the test" >&2; exit 1; }
real_python="$(command -v python3)" || { echo "python3 not found: the script needs it, and so does this" >&2; exit 1; }

KEY="SENTINEL-STEAM-KEY"
STEAM_ID="76561198000000001"
# Whatever else is secret in the files of a run. None of it is to be seen either.
SECRETS=("$KEY" "OLD-DEVICE-STEAM-KEY" "DEVICE-RA-KEY" "DEVICE-IGDB-SECRET" "PC-RA-KEY")

failures=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1${2:+  — $2}"; failures=$((failures + 1)); }
check() {  # description, then a command that is to succeed
    local what="$1"; shift
    if "$@"; then pass "$what"; else fail "$what"; fi
}
check_not() {
    local what="$1"; shift
    if "$@"; then fail "$what"; else pass "$what"; fi
}

sandbox=""
cleanup() { [[ -n "$sandbox" && -d "$sandbox" ]] && rm -rf "$sandbox"; }
trap cleanup EXIT

new_sandbox() {
    cleanup
    sandbox="$(mktemp -d "${TMPDIR:-/tmp}/bridge-cable-test.XXXXXX")"
    device="$sandbox/device"          # stands for /sdcard/PegasusData
    pc="$sandbox/pc-credentials.json"
    adb_log="$sandbox/adb.log"        # one line for each call of adb
    seen="$sandbox/seen.txt"          # every argument and every environment, as they were
    mkdir -p "$sandbox/bin" "$sandbox/home" "$device"
    : > "$adb_log"; : > "$seen"

    cat > "$sandbox/bin/adb" <<FAKE
#!$BASH
{ printf 'adb'; printf ' [%s]' "\$@"; printf '\n'; } >> "$adb_log"
{ printf '%s\n' "\$@"; export -p; } >> "$seen"
if [[ "\${1:-}" == -s ]]; then shift 2; fi
if [[ -n "\${FAKE_NO_DEVICE:-}" ]]; then echo "adb: no devices/emulators found" >&2; exit 1; fi
if [[ "\${1:-}" != shell ]]; then echo "fake adb: only shell is played" >&2; exit 64; fi
shift
cmd="\$*"
cmd="\${cmd//\/sdcard\/PegasusData/$device}"
# A device that loses the end of what it is sent.
if [[ -n "\${FAKE_TRUNCATE:-}" ]]; then cmd="\${cmd//cat > /head -c 40 > }"; fi
export PATH=/usr/bin:/bin
exec sh -c "\$cmd"
FAKE
    cat > "$sandbox/bin/python3" <<FAKE
#!$BASH
{ printf '%s\n' "\$@"; export -p; } >> "$seen"
exec "$real_python" "\$@"
FAKE
    chmod +x "$sandbox/bin/adb" "$sandbox/bin/python3"

    cat > "$pc" <<JSON
{
  "schemaVersion": 1,
  "ra": { "user": "pc-user", "apiKey": "PC-RA-KEY" },
  "steam": { "apiKey": "$KEY", "steamId": "$STEAM_ID" },
  "updatedAt": 1760000000
}
JSON
}

# A device's credentials.json with blocks of its own, a Steam block from before
# among them, and values a careless rewrite would change.
device_file_with_blocks() {
    mkdir -p "$device/config"
    cat > "$device/config/credentials.json" <<'JSON'
{
  "schemaVersion": 1,
  "ra": { "user": "device-user", "apiKey": "DEVICE-RA-KEY" },
  "igdb": { "clientId": "abc", "clientSecret": "DEVICE-IGDB-SECRET",
            "cachedToken": "tok", "cachedTokenExp": 1790000000 },
  "steam": { "apiKey": "OLD-DEVICE-STEAM-KEY", "steamId": "76561198000000009" },
  "screenScraper": { "devId": "d", "devPassword": "p / \"quoted\" è \\ $HOME `x`",
                     "softname": "PegasusBridge" },
  "odd": [1, 2.5, -0.0, 1e3, true, null, { "deep": "ü", "": [] }],
  "updatedAt": 1759999999
}
JSON
    cp "$device/config/credentials.json" "$sandbox/device-before.json"
}

# What a Bridge keeps under cache/steam, for two accounts, and what lies
# around it that is nobody's to remove.
kept_files() {
    local a="$device/cache/steam/76561198000000009" b="$device/cache/steam/76561198000000001"
    mkdir -p "$a/progress" "$b/progress" "$device/cache/steam/schema" \
             "$device/cache/steam/1234/progress" "$device/cache/steam/7656119800000000x" \
             "$device/cache/ra-lists"
    echo '{}' > "$a/library.json";  echo '{}' > "$a/progress/367520.json"
    echo '{}' > "$b/library.json";  echo '{}' > "$b/library.json.tmp"; echo '{}' > "$b/progress/440.json"
    echo 'not the Bridge'"'"'s' > "$b/kept-by-hand.txt"
    echo '{}' > "$device/cache/steam/schema/367520.english.json"
    echo '{}' > "$device/cache/steam/1234/library.json"
    echo '{}' > "$device/cache/steam/7656119800000000x/library.json"
    echo 'x'  > "$device/cache/ra-lists/7.tsv"
}

rc=0
run_script() {  # [NAME=value]... -- arguments
    local extra=()
    while [[ $# -gt 0 && "$1" != -- ]]; do extra+=("$1"); shift; done
    shift
    env -i HOME="$sandbox/home" PATH="$sandbox/bin" ${extra[@]+"${extra[@]}"} \
        "$BASH" "$script" "$@" > "$sandbox/out.txt" 2>&1 </dev/null
    rc=$?
}

# True when the JSON in file $1 makes the Python expression $2 true, as `d`.
json_is() {
    "$real_python" -c '
import json, sys
try:
    d = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    sys.exit(2)
sys.exit(0 if eval(sys.argv[2]) else 1)' "$1" "$2"
}

# True when the two files hold the same JSON but for the block named.
same_but_for() {  # file, file, block
    "$real_python" -c '
import json, sys
a = json.load(open(sys.argv[1], encoding="utf-8"))
b = json.load(open(sys.argv[2], encoding="utf-8"))
a.pop(sys.argv[3], None); b.pop(sys.argv[3], None)
sys.exit(0 if a == b and list(a) == list(b) else 1)' "$1" "$2" "$3"
}

logged()      { grep -qF -- "$1" "$adb_log"; }
said()        { grep -qF -- "$1" "$sandbox/out.txt"; }
nothing_seen_of_secrets() {
    local s
    for s in "${SECRETS[@]}"; do
        grep -qF -- "$s" "$seen" "$adb_log" "$sandbox/out.txt" && { echo "       seen: $s"; return 1; }
    done
    return 0
}
# No command that writes, renames or removes was sent to the device.
device_untouched() {
    ! grep -qE 'cat >|mv |rm |mkdir |rmdir ' "$adb_log"
}
BLOCK='d["steam"] == {"apiKey": "'"$KEY"'", "steamId": "'"$STEAM_ID"'"}'

# ── 1. a device with a file ────────────────────────────────────────────────
echo "== a device whose credentials.json has other blocks"
new_sandbox; device_file_with_blocks; kept_files
run_script -- --credentials "$pc"
check "the script ends well" test $rc -eq 0
f="$device/config/credentials.json"
check "the steam block is the PC's"                    json_is "$f" "$BLOCK"
check "every other block is what it was, in its place" same_but_for "$f" "$sandbox/device-before.json" steam
check "the block stayed where the old one was"         json_is "$f" 'list(d).index("steam") == 3'
check_not "nothing else of the PC's file came along"   grep -qF "PC-RA-KEY" "$f"
check "it was written under the temporary name"        logged "cat > /sdcard/PegasusData/config/credentials.json.cable]"
check "and renamed over the real file with mv"         logged "mv -f /sdcard/PegasusData/config/credentials.json.cable /sdcard/PegasusData/config/credentials.json]"
check_not "the temporary name is not the Bridge's own" logged "credentials.json.tmp"
check_not "the real file is never written in place"    logged "> /sdcard/PegasusData/config/credentials.json]"
check_not "no temporary file is left"                  test -e "$f.cable"
a="$device/cache/steam/76561198000000009"; b="$device/cache/steam/76561198000000001"
check_not "an account's library is removed"            test -e "$a/library.json"
check_not "and its unlock lists"                       test -e "$a/progress"
check_not "and its folder, left empty"                 test -e "$a"
check_not "the other account's library too"            test -e "$b/library.json"
check_not "and a write of it that was cut short"       test -e "$b/library.json.tmp"
check_not "and its unlock lists"                       test -e "$b/progress"
check "a file there that is not the Bridge's stays"    test -f "$b/kept-by-hand.txt"
check "the schemas stay"                               test -f "$device/cache/steam/schema/367520.english.json"
check "a folder of digits that are not 17 stays"       test -f "$device/cache/steam/1234/library.json"
check "and one of 17 characters that are not all digits" test -f "$device/cache/steam/7656119800000000x/library.json"
check "another cache stays"                            test -f "$device/cache/ra-lists/7.tsv"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
check "the end says what was done, and for which SteamID" said "SteamID $STEAM_ID"
# The checks that something is in no recorded line say little of a record
# that is empty, or that could not have held it.
check "python3's arguments were written down"          grep -qxF "merge" "$seen"
check "and adb's"                                      grep -qxF "cat /sdcard/PegasusData/config/credentials.json" "$seen"
check "and an environment"                             grep -qF "HOME=" "$seen"
check "the device now holds the key, to show the search above could find one" grep -qF "$KEY" "$f"

echo "== the same again, with the block already there"
cp "$f" "$sandbox/after-first.json"
run_script -- --credentials "$pc"
check "the script ends well" test $rc -eq 0
check "the file is what it was" cmp -s "$f" "$sandbox/after-first.json"

# ── 2. a device without a file ─────────────────────────────────────────────
echo "== a device with no credentials.json"
new_sandbox
run_script -- --credentials "$pc"
check "the script ends well" test $rc -eq 0
f="$device/config/credentials.json"
check "the file holds that block and no other" json_is "$f" "$BLOCK and list(d) == ['steam']"
check_not "no temporary file is left"          test -e "$f.cable"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
check "the end says there was no file"         said "There was no file there"

echo "== a device the Bridge has never run on"
new_sandbox; rmdir "$device"
run_script -- --credentials "$pc"
check "the script refuses" test $rc -ne 0
check "and says why"       said "has no /sdcard/PegasusData"
check "nothing is written" device_untouched
check_not "no data root is made for the key to lie in" test -e "$device"

# ── 3. a device with a broken file ─────────────────────────────────────────
broken() {  # what it is, then the bytes as printf takes them
    echo "== a device whose credentials.json is $1"
    new_sandbox; kept_files
    mkdir -p "$device/config"
    printf "$2" > "$device/config/credentials.json"
    cp "$device/config/credentials.json" "$sandbox/device-before.json"
    run_script -- --credentials "$pc"
    check "the script refuses"       test $rc -ne 0
    check "and says why"             said "not a JSON object: nothing was written"
    check "the file is byte for byte what it was" cmp -s "$device/config/credentials.json" "$sandbox/device-before.json"
    check "nothing is written, renamed or removed" device_untouched
    check_not "no temporary file is left" test -e "$device/config/credentials.json.cable"
    check "what the Bridge keeps is still there" test -f "$device/cache/steam/76561198000000009/library.json"
    check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
}
broken "cut short"                 '{"ra": {"user": "device-user", "apiKey": "DEVICE-RA-KEY"'
broken "empty"                     ''
broken "a list"                    '[1, 2, 3]\n'
broken "an object and then more"   '{"ra": {}}\n{"steam": {}}\n'
broken "an object and then a NUL"  '{"ra": {}}\000'
broken "not UTF-8"                 '{"ra": {"user": "\377\376"}}'
broken "an object with a NaN"      '{"ra": {"n": NaN}}'

echo "== the same broken file, and --remove"
new_sandbox; kept_files
mkdir -p "$device/config"
printf '{"steam": {"apiKey": "OLD-DEVICE-STEAM-KEY"' > "$device/config/credentials.json"
cp "$device/config/credentials.json" "$sandbox/device-before.json"
run_script -- --remove
check "the script refuses" test $rc -ne 0
check "the file is byte for byte what it was" cmp -s "$device/config/credentials.json" "$sandbox/device-before.json"
check "nothing is written, renamed or removed" device_untouched
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

# ── 4. a block on the PC that is not one to copy ───────────────────────────
refused() {  # what it is, the steam block as JSON or "" for none, what is said
    echo "== a PC whose steam block has $1"
    new_sandbox; device_file_with_blocks
    if [[ -n "$2" ]]; then printf '{"ra": {"user": "u"}, "steam": %s}\n' "$2" > "$pc"
    else printf '{"ra": {"user": "u"}}\n' > "$pc"; fi
    run_script -- --credentials "$pc"
    check "the script refuses"     test $rc -ne 0
    check "and says why"           said "$3"
    check_not "adb is not started" test -s "$adb_log"
    check "the device's file is what it was" cmp -s "$device/config/credentials.json" "$sandbox/device-before.json"
    check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
}
refused "an empty key"             '{"apiKey": "", "steamId": "'"$STEAM_ID"'"}'                 "blank apiKey"
refused "a key of spaces"          '{"apiKey": " \t ", "steamId": "'"$STEAM_ID"'"}'            "blank apiKey"
refused "no key"                   '{"steamId": "'"$STEAM_ID"'"}'                               "blank apiKey"
refused "a key that is a number"   '{"apiKey": 12345, "steamId": "'"$STEAM_ID"'"}'              "blank apiKey"
refused "a SteamID of 16 digits"   '{"apiKey": "'"$KEY"'", "steamId": "7656119800000000"}'      "not 17 digits"
refused "a SteamID of 18 digits"   '{"apiKey": "'"$KEY"'", "steamId": "765611980000000011"}'    "not 17 digits"
refused "a SteamID with a letter"  '{"apiKey": "'"$KEY"'", "steamId": "7656119800000000a"}'     "not 17 digits"
refused "a SteamID and a newline"  '{"apiKey": "'"$KEY"'", "steamId": "'"$STEAM_ID"'\n"}'      "not 17 digits"
refused "a SteamID in full-width digits" '{"apiKey": "'"$KEY"'", "steamId": "\uff17\uff16\uff15\uff16\uff11\uff11\uff19\uff18\uff10\uff10\uff10\uff10\uff10\uff10\uff10\uff10\uff11"}' "not 17 digits"
refused "no SteamID"               '{"apiKey": "'"$KEY"'"}'                                     "not 17 digits"
refused "a list for a block"       '["'"$KEY"'", "'"$STEAM_ID"'"]'                              "no steam block"
refused "nothing at all"           ''                                                           "no steam block"

echo "== a PC whose credentials file is not JSON, or is not there"
new_sandbox; device_file_with_blocks
printf '{"steam": {"apiKey": "%s", "steamId": "%s"' "$KEY" "$STEAM_ID" > "$pc"
run_script -- --credentials "$pc"
check "a file cut short is refused" test $rc -ne 0
check "and adb is not started"      test ! -s "$adb_log"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
run_script -- --credentials "$sandbox/not-there.json"
check "a file that is not there is refused" test $rc -ne 0
check "and adb is not started"      test ! -s "$adb_log"

# ── 5. --remove ────────────────────────────────────────────────────────────
echo "== --remove on a device that has the block"
new_sandbox; device_file_with_blocks; kept_files
run_script -- --remove
check "the script ends well" test $rc -eq 0
f="$device/config/credentials.json"
check "the steam block is gone"                        json_is "$f" '"steam" not in d'
check "every other block is what it was, in its place" same_but_for "$f" "$sandbox/device-before.json" steam
check "it was renamed over the real file with mv"      logged "mv -f /sdcard/PegasusData/config/credentials.json.cable /sdcard/PegasusData/config/credentials.json]"
check_not "no temporary file is left"                  test -e "$f.cable"
check_not "an account's library is removed"            test -e "$device/cache/steam/76561198000000009/library.json"
check_not "and its unlock lists"                       test -e "$device/cache/steam/76561198000000001/progress"
check "the schemas stay"                               test -f "$device/cache/steam/schema/367520.english.json"
check_not "the PC's file is not read: python3 is given no file" grep -qF "$pc" "$seen"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

echo "== --remove on a device whose file has no steam block"
new_sandbox; kept_files
mkdir -p "$device/config"
printf '{"ra": {"user": "device-user", "apiKey": "DEVICE-RA-KEY"}}' > "$device/config/credentials.json"
cp "$device/config/credentials.json" "$sandbox/device-before.json"
run_script -- --remove
check "the script ends well" test $rc -eq 0
check "the file is byte for byte what it was" cmp -s "$device/config/credentials.json" "$sandbox/device-before.json"
check_not "it is not written again"           logged "cat >"
check_not "what was kept is removed all the same" test -e "$device/cache/steam/76561198000000009/library.json"
check "the end says there was no block"       said "had no steam block"

echo "== --remove on a device with no credentials.json"
new_sandbox; kept_files
run_script -- --remove
check "the script ends well" test $rc -eq 0
check_not "no file is made"  test -e "$device/config/credentials.json"
check_not "what was kept is removed all the same" test -e "$device/cache/steam/76561198000000001/library.json"

# ── 6. which device, and none ──────────────────────────────────────────────
echo "== --serial"
new_sandbox; device_file_with_blocks
run_script -- --credentials "$pc" --serial "R5CT-0123 ABC"
check "the script ends well" test $rc -eq 0
check "adb was started"      test -s "$adb_log"
check_not "every call of adb names the device, as one argument" grep -qv '^adb \[-s\] \[R5CT-0123 ABC\] \[shell\] ' "$adb_log"
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"

echo "== no --serial"
new_sandbox; device_file_with_blocks
run_script -- --credentials "$pc"
check_not "no call of adb names a device" grep -qF '[-s]' "$adb_log"
check_not "every call is adb shell" grep -qv '^adb \[shell\] \[' "$adb_log"

echo "== no device answers"
new_sandbox; device_file_with_blocks
run_script FAKE_NO_DEVICE=1 -- --credentials "$pc"
check "the script refuses" test $rc -ne 0
check "and says so"        said "no device answered adb"
check "the device's file is what it was" cmp -s "$device/config/credentials.json" "$sandbox/device-before.json"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

# ── 7. a device that does not hold what it was sent ────────────────────────
echo "== a device that loses the end of what is written"
new_sandbox; device_file_with_blocks; kept_files
run_script FAKE_TRUNCATE=1 -- --credentials "$pc"
check "the script refuses" test $rc -ne 0
check "and says the file was left" said "its credentials.json was left as it was"
check "the real file is byte for byte what it was" cmp -s "$device/config/credentials.json" "$sandbox/device-before.json"
check_not "it is not renamed"            logged "mv "
check_not "the cut file does not stay"   test -e "$device/config/credentials.json.cable"
check "what the Bridge keeps is still there" test -f "$device/cache/steam/76561198000000009/library.json"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

# ── 8. where the PC's file is looked for ───────────────────────────────────
echo "== the PC's file where install.sh puts the data root"
new_sandbox
mkdir -p "$sandbox/home/.local/share/pegasus-bridge/config"
mv "$pc" "$sandbox/home/.local/share/pegasus-bridge/config/credentials.json"
run_script --
check "with no option it is found under HOME" test $rc -eq 0
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"

new_sandbox
mkdir -p "$sandbox/elsewhere/config"
mv "$pc" "$sandbox/elsewhere/config/credentials.json"
run_script -- --prefix "$sandbox/elsewhere/"
check "--prefix names another data root" test $rc -eq 0
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"
run_script --
check "and without it nothing is found under an empty HOME" test $rc -ne 0

echo "== a PC whose locale cannot write every character of the device's file"
new_sandbox; device_file_with_blocks
run_script PYTHONUTF8=0 PYTHONIOENCODING=ascii -- --credentials "$pc"
check "the script ends well" test $rc -eq 0
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"
check "every other block is what it was, in its place" same_but_for "$device/config/credentials.json" "$sandbox/device-before.json" steam

# ── 9. a shell told to print what it does ──────────────────────────────────
echo "== bash -x"
new_sandbox; device_file_with_blocks
env -i HOME="$sandbox/home" PATH="$sandbox/bin" "$BASH" -x "$script" --credentials "$pc" \
    > "$sandbox/out.txt" 2>&1 </dev/null
check "the script ends well" test $? -eq 0
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

echo "== a shell told to export what it assigns"
new_sandbox; device_file_with_blocks
env -i HOME="$sandbox/home" PATH="$sandbox/bin" "$BASH" -a "$script" --credentials "$pc" \
    > "$sandbox/out.txt" 2>&1 </dev/null
check "bash -a: the script ends well" test $? -eq 0
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
new_sandbox; device_file_with_blocks
run_script SHELLOPTS=allexport -- --credentials "$pc"
check "allexport in SHELLOPTS: the script ends well" test $rc -eq 0
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
run_script SHELLOPTS=allexport -- --remove
check "and with --remove" test $rc -eq 0
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

# A variable that came in with the environment is exported again with whatever
# is assigned to it, and a local of its name is too.
echo "== an environment that holds the names the script keeps the files under"
new_sandbox; device_file_with_blocks
run_script merged=x without=x text=x written=x -- --credentials "$pc"
check "the script ends well" test $rc -eq 0
check "the steam block is the PC's" json_is "$device/config/credentials.json" "$BLOCK"
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets
check "and an environment was written down" grep -qF "PATH=" "$seen"
run_script merged=x without=x text=x written=x -- --remove
check "and with --remove" test $rc -eq 0
check "the steam block is gone" json_is "$device/config/credentials.json" '"steam" not in d'
check "the key is in no argument, no environment and no output" nothing_seen_of_secrets

# ── 10. the rest ───────────────────────────────────────────────────────────
echo "== an option it does not know, and --help"
new_sandbox
run_script -- --frobnicate
check "an unknown option is refused" test $rc -ne 0
check_not "and adb is not started"   test -s "$adb_log"
run_script -- --help
check "--help ends well"             test $rc -eq 0
check "and prints the head of the script" said "--remove"
check_not "and adb is not started"   test -s "$adb_log"

echo
if [[ $failures -eq 0 ]]; then
    echo "all checks passed"
else
    echo "$failures check(s) failed"
fi
exit $((failures > 0))
