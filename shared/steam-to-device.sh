#!/usr/bin/env bash
# Copies the Steam block of this PC's Bridge to an Android device over the
# cable, or takes it off the device again.
#
#   ./steam-to-device.sh [--prefix DIR | --credentials FILE] [--serial SERIAL]
#   ./steam-to-device.sh --remove [--serial SERIAL]
#
# The block is the user's own Steam Web API key and SteamID, which the
# `steam-account` source needs. On the PC a theme's settings screen writes it,
# through the daemon. On Android nothing on the device writes it: the verbs
# there travel as intents, which is no place for a key. So it is read here,
# out of the PC Bridge's credentials.json, and put into the device's
# /sdcard/PegasusData/config/credentials.json with adb.
#
#   --prefix DIR        the PC Bridge's data root, as install.sh's --prefix
#                       (default ~/.local/share/pegasus-bridge); the file read
#                       is <DIR>/config/credentials.json
#   --credentials FILE  read the block from this file instead
#   --serial SERIAL     the device, when more than one is attached (adb -s)
#   --remove            take the block out of the device's file; the PC's file
#                       is not read
#
# Only the steam block of the device's file changes. Every other block is
# written back as it was read. A device with no credentials.json gets one with
# that block alone. A file there that is not JSON stops this before anything is
# written. The new file is written beside the old one under another name, read
# back, and only then renamed over it.
#
# Afterwards what the device's Bridge keeps of an account is removed, its
# library and its unlock lists under cache/steam: they are named by the
# SteamID, and would answer for the old key for ten minutes more. The schemas
# stay, being every account's.
#
# The key is never shown, and is in no command line and no environment: it goes
# from this script to adb through a pipe. It needs adb and python3, and a
# device with USB debugging on which the Bridge has run once.

# First of all: under `bash -x` every assignment below would be printed, the
# one that holds the key among them.
set +x
set -euo pipefail

prefix="$HOME/.local/share/pegasus-bridge"
credentials=""
serial=""
remove=0

usage() {
    local line n=0
    while IFS= read -r line; do
        n=$((n + 1))
        [[ $n -ge 2 ]] || continue
        [[ "$line" == \#* ]] || break
        line="${line#\#}"
        printf '%s\n' "${line# }"
    done < "${BASH_SOURCE[0]}"
}

die() { echo "steam-to-device: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --prefix)      [[ $# -ge 2 ]] || die "--prefix needs a directory"; prefix="${2%/}"; shift 2 ;;
        --credentials) [[ $# -ge 2 ]] || die "--credentials needs a file"; credentials="$2"; shift 2 ;;
        --serial|-s)   [[ $# -ge 2 ]] || die "--serial needs a device's serial"; serial="$2"; shift 2 ;;
        --remove)      remove=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done

pc_file="${credentials:-$prefix/config/credentials.json}"

command -v adb >/dev/null 2>&1     || die "adb not found: it comes with Android's platform tools"
command -v python3 >/dev/null 2>&1 || die "python3 not found: it is what reads and writes the JSON"

adb=(adb)
[[ -z "$serial" ]] || adb=(adb -s "$serial")

# The Android data root is this one path on every device, and no name below is
# made of anything but these constants: nothing here needs quoting for the
# device's shell.
device_root="/sdcard/PegasusData"
device_dir="$device_root/config"
device_file="$device_dir/credentials.json"
# Not credentials.json.tmp, which is the name the Bridge itself writes through.
device_tmp="$device_dir/credentials.json.cable"
steam_id_glob="[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]"

# Everything that reads JSON. The PC's file is named to it as an argument and
# read by it; the device's file comes in on standard input and the new one goes
# out on standard output. What it says of a file is a sentence of its own,
# never a piece of the file.
read -r -d '' json_tool <<'PY' || true
import json
import re
import sys


def stop(why, status=1):
    sys.stderr.write("steam-to-device: " + why + "\n")
    sys.exit(status)


def no_constant(name):
    raise ValueError(name)


def parse(raw):
    doc = json.loads(raw.decode("utf-8-sig"), parse_constant=no_constant)
    if not isinstance(doc, dict):
        raise ValueError("not an object")
    return doc


def pc_block(path):
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except OSError:
        stop("the PC's credentials file could not be read")
    try:
        doc = parse(raw)
    except Exception:
        stop("the PC's credentials file is not a JSON object")
    steam = doc.get("steam")
    if not isinstance(steam, dict):
        stop("the PC's credentials file has no steam block: save the key in the theme's settings first")
    key, steam_id = steam.get("apiKey"), steam.get("steamId")
    if isinstance(steam_id, int) and not isinstance(steam_id, bool):
        steam_id = str(steam_id)
    if not isinstance(key, str) or not key.strip():
        stop("the steam block on the PC has a blank apiKey")
    if not isinstance(steam_id, str) or not re.fullmatch("[0-9]{17}", steam_id):
        stop("the steam block on the PC has a steamId that is not 17 digits")
    return {"apiKey": key, "steamId": steam_id}


def device_doc():
    try:
        return parse(sys.stdin.buffer.read())
    except Exception:
        stop("the device's credentials.json is not a JSON object: nothing was written")


def others(doc):
    return {k: v for k, v in doc.items() if k != "steam"}


def emit(doc, before, steam):
    text = json.dumps(doc, indent=2, ensure_ascii=False)
    # What is about to be written, read as the Bridge will read it: every
    # other block as it was, and the steam block as it was meant to be.
    try:
        back = parse(text.encode("utf-8"))
    except Exception:
        back = None
    if back is None or others(back) != others(before) or back.get("steam") != steam:
        stop("the new credentials.json did not read back as it should: nothing was written")
    # As bytes: the file is UTF-8 whatever this machine's locale is.
    sys.stdout.buffer.write(text.encode("utf-8") + b"\n")


mode = sys.argv[1]
if mode == "check":
    sys.stdout.write(pc_block(sys.argv[2])["steamId"] + "\n")
elif mode == "merge":
    block = pc_block(sys.argv[2])
    before = device_doc() if sys.argv[3] == "present" else {}
    after = dict(before)
    after["steam"] = block
    emit(after, before, block)
elif mode == "remove":
    before = device_doc()
    if "steam" not in before:
        sys.exit(3)
    emit(others(before), before, None)
else:
    stop("unknown mode")
PY

# A command on the device. adb hands its own standard input on to the device,
# so it is given none, but for the one write: left with the script's, it would
# take whatever was waiting there.
on_device() { "${adb[@]}" shell "$1" </dev/null; }
to_device() { "${adb[@]}" shell "$1"; }

# The new file lies on the device under its temporary name from the write to
# the rename. Whatever ends the script in between, it does not stay there.
tmp_on_device=0
cleanup() {
    if [[ $tmp_on_device -eq 1 ]]; then
        "${adb[@]}" shell "rm -f $device_tmp" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# Writes $1 as the device's credentials.json: under the temporary name, read
# back and compared, then renamed. printf is the shell's own, so the text is in
# no process's arguments on its way to adb.
write_device_file() {
    local text="$1" written
    tmp_on_device=1
    printf '%s\n' "$text" | to_device "mkdir -p $device_dir && cat > $device_tmp" \
        || die "could not write on the device; its credentials.json was left as it was"
    written="$(on_device "cat $device_tmp")" \
        || die "could not read back what was written; the device's credentials.json was left as it was"
    [[ "$written" == "$text" ]] \
        || die "the device does not hold what was sent; its credentials.json was left as it was"
    on_device "mv -f $device_tmp $device_file" \
        || die "could not rename the new file over the device's credentials.json"
    tmp_on_device=0
}

# The library and the unlock lists of every account, as SteamAccountOps.forget
# drops them on the PC. Prints how many account folders there were.
forget_on_device() {
    on_device "n=0; for d in $device_root/cache/steam/$steam_id_glob; do [ -d \"\$d\" ] || continue; rm -f \"\$d/library.json\" \"\$d/library.json.tmp\"; rm -rf \"\$d/progress\"; rmdir \"\$d\" 2>/dev/null; n=\$((n + 1)); done; echo \$n"
}

# ── The PC's block, before the device is asked anything ─────────────────────

steam_id=""
if [[ $remove -eq 0 ]]; then
    [[ -f "$pc_file" ]] || die "no credentials file at $pc_file (see --prefix and --credentials)"
    steam_id="$(python3 -c "$json_tool" check "$pc_file")" || exit 1
fi

# ── What the device has ─────────────────────────────────────────────────────

state="$(on_device "if [ ! -d $device_root ]; then echo no-root; elif [ -f $device_file ]; then echo present; elif [ -e $device_file ]; then echo other; else echo absent; fi")" \
    || die "no device answered adb: is one attached, with USB debugging allowed? (adb devices)"
state="${state//$'\r'/}"
case "$state" in
    present|absent) ;;
    no-root) die "the device has no $device_root: install the Bridge there and start it once" ;;
    other)   die "$device_file on the device is not a file" ;;
    *)       die "the device gave an answer that was not understood" ;;
esac

# The device's file goes from adb straight into the reader. It holds the
# device's other keys, and is kept in no variable here.
read_device_file() { on_device "cat $device_file"; }

# ── Taking the block off ────────────────────────────────────────────────────

if [[ $remove -eq 1 ]]; then
    had_block=0
    if [[ "$state" == present ]]; then
        status=0
        without="$(read_device_file | python3 -c "$json_tool" remove)" || status=$?
        case $status in
            0) write_device_file "$without"; had_block=1 ;;
            3) ;;
            *) exit 1 ;;
        esac
    fi
    accounts="$(forget_on_device)" \
        || die "what the device's Bridge keeps of the account could not be removed"
    accounts="${accounts//$'\r'/}"
    echo
    if [[ $had_block -eq 1 ]]; then
        echo "done. The steam block was taken out of the device's credentials.json; every other block is as it was."
    else
        echo "done. The device's credentials.json had no steam block; it was left as it was."
    fi
    echo "What its Bridge kept of an account was removed ($accounts account folder(s)); the schemas were left."
    exit 0
fi

# ── Putting the block on ────────────────────────────────────────────────────

if [[ "$state" == present ]]; then
    merged="$(read_device_file | python3 -c "$json_tool" merge "$pc_file" present)" || exit 1
else
    merged="$(python3 -c "$json_tool" merge "$pc_file" absent </dev/null)" || exit 1
fi
write_device_file "$merged"

accounts="$(forget_on_device)" \
    || die "the block is on the device, but what its Bridge keeps of the account could not be removed: answers from the old key may last ten minutes"
accounts="${accounts//$'\r'/}"

echo
echo "done. The device's credentials.json has the steam block of $pc_file, for SteamID $steam_id."
if [[ "$state" == present ]]; then
    echo "Every other block in it is as it was. The key is not shown."
else
    echo "There was no file there, and the block is all it holds. The key is not shown."
fi
echo "What its Bridge kept of an account was removed ($accounts account folder(s)); the schemas were left."
