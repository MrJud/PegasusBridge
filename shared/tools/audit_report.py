#!/usr/bin/env python3
"""Reads the table an audit wrote and says what is in it.

An audit is one scan of a library, run as the daemon runs it, with a row for
every file (ScanAudit.kt; `./gradlew :daemon:audit -PauditArgs='--audit=<folder>
--out=<file.tsv>'`). This script counts the rows by collection and state, looks
for hashes that cannot be right, and compares the table with the one an earlier
build wrote for the same library:

    ./tools/audit_report.py TABLE [--baseline TABLE] [--oracle ANSWERS]
                            [--min-identified N] [--max-junk N] [--max-changed N]
                            [--all]

It is how a change to hashing is judged on real files: the table before the
change is kept, the table after it is compared, and every file that differs is
named with the columns that moved. It asks nobody and reads no ROM.

A collection is the first folder under the root the audit was given, which the
table names in a `# root` line. A file that sits in the root itself is counted
under the root's own name.

JUNK. Four kinds of hash are wrong whatever RetroAchievements would say of
them, so they can be found with no list of right answers:

  J1  the hash of a disc descriptor (cue, gdi, m3u, ccd, toc) that is the MD5
      of the descriptor's own bytes. A descriptor names the tracks to hash; its
      text is not the game.
  J2  the MD5 of the file's name, which is how an arcade set is hashed, for a
      file that is not in an arcade collection. rcheevos does it for any zip
      it can make nothing else of.
  J3  any hash at all for a file in a collection RetroAchievements has no
      algorithm for.
  J4  a hash made as a Game Boy ROM (console 4) for a file that is not one.
      Game Boy is hashed whole with no check of the header, and it is the
      first console rcheevos tries for an extension it does not know.

J2 and J3 ask what collection a file is in, and three things answer: the folder
it is under at the root, which is where the library keeps it, and the
`platform` and `dirName` columns, which are what the scan took it for and
handed the hasher: the short name its collection declares, and the name of the
folder that collection is kept in. A table written before the scan knew of
collections has no `dirName`, and its `platform` is the name of the file's own
folder, "Game" for switch/Game/Game.iso. Any one is enough: a file is in a
collection with no algorithm when one of the three names one, and in an arcade
collection when one of the three does. So an archive in a folder called mame is
not J2 wherever that folder is, because the scan hashes it by name on purpose,
and one in a folder under arcade is not J2 either.

For a file taken out of an archive the extension that counts is the entry's.

BASELINE. Two tables are compared by path, on the columns that say what the
scan decided: platform, state, console, hash, fileMd5, archiveEntry and asked.
`ms` is never compared, since no two runs take the same time, and `size` is the
library's business. A file whose `detail` alone differs is listed apart and is
not counted as changed: the wording of a reason may change where the verdict
does not. So is a file whose `fileMd5` alone went from a value to nothing:
a scan takes that digest only of a file it has read whole (ArchiveAwareHasher,
digestInScan), and a build that leaves it for later has changed no verdict.
Lines beginning `#` are not rows, here or in the baseline, and are
not compared as rows are. The ones that say how a run was made are read all
the same, the roots, the two kinds of skip and where the answers came from,
and a comparison says which of them the two runs do not share, and says when
the baseline's scan stopped early: every changed row such a pair shows is
otherwise put down to the build. The `# recipe` line is what the build judges a
file by (HashRecipe.kt), and where the two tables do not have the same one the
comparison prints both, so that a changed row can be held against the part of
the line that moved.

READ. A table of a build that counts them has a `read` column: the bytes the
scan asked the system for while it hashed the file, empty for a file the
hasher was never handed. Where any row has it, the report repeats the table's
own `# hasher` and `# read` lines and adds the column up by collection, beside
the size of the files it is of: a file read twice shows as twice its size, and
one the hash needed the start of as less than it. They are bytes asked for,
whether the disk or the memory gave them, and say nothing of time. The column
is never compared: what a file costs to read is what a change is made to move.

ORACLE. A file of answers already known: hash, game id, then date, source and
title, with tabs between (the format `--oracle=` of the audit reads). With it,
a file counts as identified when the scan matched it or when its hash is in
the oracle under a real game id, and with a baseline as well the files whose
hash was in the oracle then are checked for still having that hash now.

Exit status 0, or 1 when fewer files are identified than --min-identified,
more are junk than --max-junk, or more differ from the baseline than
--max-changed; 2 when a table cannot be read. Each list is cut at 20 lines
unless --all is given. tools/audit_report_test.py tests it.
"""

import argparse
import hashlib
import sys
from collections import Counter, OrderedDict
from pathlib import PurePosixPath, PureWindowsPath

# What ScanAudit writes, in its order. `path` and `state` are the two a table
# cannot do without; a table from a later build may have more columns.
COLUMNS = ["path", "platform", "dirName", "extension", "size", "state", "console",
           "hash", "fileMd5", "archiveEntry", "detail", "asked", "ms", "read"]
COMPARED = ["platform", "state", "console", "hash", "fileMd5", "archiveEntry", "asked"]

# The states of the ledger (ScanLedger.State), every one, in the order a person
# wants to read them: what was identified, what was asked about, what was
# passed over, what could not be hashed. A state this list does not have,
# which a later build may add, goes after them by name.
STATE_ORDER = ["MATCHED", "NOT_FOUND", "KNOWN_UNSUPPORTED", "API_RETRY", "UNSUPPORTED",
               "PLACEHOLDER", "AMBIGUOUS_ARCHIVE", "NO_PLAYABLE_ENTRY", "UNSUPPORTED_FORMAT",
               "UNHASHABLE", "HASH_FAILED"]

DESCRIPTORS = {"cue", "gdi", "m3u", "ccd", "toc"}
# The two lists below are the console table's (RcConsoles.ROWS in the Bridge's
# core), by every name a row goes by, spelt as platform_key spells a name. They
# are copies, since this script reads no Kotlin, and AuditReportListsTest in
# the hasher's tests reads this file and fails when either is not the table's:
# a row added there and not here is a collection rule J2 or J3 does not know.
#
# Collections whose files rcheevos is right to hash by name: the arcade rows.
ARCADE = {"arcade", "mame", "fbneo", "fba", "atomiswave", "neogeo", "naomi",
          "cps1", "cps2", "cps3"}
# Collections rcheevos has no algorithm for, or RetroAchievements no console:
# the rows that are not hashable.
NO_ALGORITHM = {"amiga", "cdimono1", "cdi", "wiiu", "3ds", "n3ds", "ps3",
                "switch", "psvita", "vita", "bbcmicro", "chailove", "cdtv",
                "pc", "windows", "android", "ios"}
GAME_BOY = "4"
GAME_BOY_EXTENSIONS = {"gb", "gbc"}

# RetroAchievements answers with an id above this for a dump it knows and does
# not consider playable as it is (VirtualGameId in RaHashLookup.kt).
VIRTUAL_ID_BASE = 1_000_000_000

LIST_LIMIT = 20

# The `#` lines that say how a run was made, as against the ones that count
# what it found or say when.
RUN_LINES = ["root", "skip-larger-than", "skip", "answers"]

UNESCAPES = {"\\": "\\", "t": "\t", "n": "\n", "r": "\r"}


class Unreadable(Exception):
    """A table or an oracle that is not what it has to be."""


def unescape(cell):
    """A cell as ScanAudit wrote it, with its backslashes taken out again."""
    if "\\" not in cell:
        return cell
    out, chars = [], iter(cell)
    for c in chars:
        if c == "\\":
            nxt = next(chars, "")
            out.append(UNESCAPES.get(nxt, "\\" + nxt))
        else:
            out.append(c)
    return "".join(out)


def read_table(path):
    """(comments, rows) of an audit's table. The comments are the `#` lines as
    (name, value) pairs; a row is a dict from each column's name to its cell,
    with "" for a column the line is too short to have."""
    try:
        with open(path, encoding="utf-8", newline="") as f:
            lines = f.read().split("\n")
    except OSError as e:
        raise Unreadable(f"{path}: {e.strerror}")
    comments, header, rows = [], None, []
    for number, line in enumerate(lines, 1):
        # A line break inside a cell is written as a backslash and a letter,
        # so one found at the end of a line was put there by an editor.
        line = line.rstrip("\r")
        if not line.strip():
            continue
        if line.startswith("#"):
            name, _, value = line[1:].partition("\t")
            comments.append((name.strip(), unescape(value)))
            continue
        cells = [unescape(c) for c in line.split("\t")]
        if header is None:
            header = cells
            for needed in ("path", "state"):
                if needed not in header:
                    raise Unreadable(f"{path}: line {number} names no '{needed}' column; "
                                     "this is not an audit's table")
            continue
        row = dict.fromkeys(header, "")
        row.update(zip(header, cells))
        rows.append(row)
    if header is None:
        raise Unreadable(f"{path}: no line that names the columns")
    seen = Counter(r["path"] for r in rows)
    twice = [p for p, n in seen.items() if n > 1]
    if twice:
        raise Unreadable(f"{path}: {len(twice)} paths have more than one row, the first {twice[0]}")
    return comments, rows


def read_oracle(path):
    """hash -> game id from a file of recorded answers."""
    answers = {}
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        raise Unreadable(f"{path}: {e.strerror}")
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.startswith("#"):
            continue
        cells = line.split("\t")
        digest = cells[0].strip().lower()
        if digest == "hash":
            continue
        try:
            answers[digest] = int(cells[1])
        except (IndexError, ValueError):
            raise Unreadable(f"{path}: line {number} is not a hash and a game id")
    return answers


def parts(path):
    """The names in a path, whichever of the two separators it is written with."""
    pure = PureWindowsPath(path) if "\\" in path and "/" not in path else PurePosixPath(path)
    return [p for p in pure.parts if p not in ("/", "\\")]


def collection_of(path, roots):
    """The first folder under the root the file was found in; for a file in
    the root itself, the root's own name. With no root that holds it, the
    folder the file is in."""
    names = parts(path)
    best = None
    for root in roots:
        r = parts(root)
        if names[:len(r)] == r and (best is None or len(r) > len(best)):
            best = r
    if best is None:
        return names[-2] if len(names) > 1 else "."
    below = names[len(best):]
    return below[0] if len(below) > 1 else (best[-1] if best else ".")


def platform_key(name):
    """A collection's name as the rules below spell it: lower case, letters
    and digits only, which is what the Bridge compares platforms by."""
    return "".join(c for c in name.lower() if c.isalnum())


def extension_of(row):
    """The extension of what was hashed: the archive's entry when there is one."""
    entry = row.get("archiveEntry", "")
    if entry:
        return entry.rpartition(".")[2].lower() if "." in entry else ""
    return row.get("extension", "").lower()


def md5(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def arcade_hashes(path):
    """What rcheevos gives for a file hashed as an arcade set: the MD5 of its
    name without the extension, or of the folder and the name when the folder
    is one FBNeo keeps a console's sets in (rc_hash_arcade in hash_rom.c).
    None for a name with no extension, or with nothing before it: the MD5 of
    no text at all is also what a file with no bytes in it hashes to."""
    names = parts(path)
    stem = names[-1].rpartition(".")[0]
    if not stem:
        return set()
    out = {md5(stem)}
    if len(names) > 1:
        out.add(md5(f"{names[-2].lower()}_{stem}"))
    return out


def junk_rules(row, roots):
    """The names of the junk rules a row breaks, none for most."""
    digest = row.get("hash", "").lower()
    if not digest:
        return []
    broken = []
    # Where the library keeps the file and what the scan took it for. The
    # second alone let through every file in a folder of its own: the scan
    # handed switch/Game/Game.iso over as "Game", and a hash there is the
    # very thing J3 is for. The folder the collection is kept in is the
    # scan's word too, and a hash under a folder called switch is the same
    # thing whatever the collection there calls itself.
    kinds = {platform_key(collection_of(row["path"], roots)), platform_key(row.get("platform", "")),
             platform_key(row.get("dirName", ""))}
    extension = extension_of(row)
    if extension in DESCRIPTORS and digest == row.get("fileMd5", "").lower():
        broken.append("J1")
    if not kinds & ARCADE and digest in arcade_hashes(row["path"]):
        broken.append("J2")
    if kinds & NO_ALGORITHM:
        broken.append("J3")
    if row.get("console", "") == GAME_BOY and extension not in GAME_BOY_EXTENSIONS:
        broken.append("J4")
    return broken


JUNK_MEANS = OrderedDict([
    ("J1", "a disc descriptor hashed as its own text"),
    ("J2", "hashed by its name outside an arcade collection"),
    ("J3", "a hash in a collection that has no algorithm"),
    ("J4", "hashed as a Game Boy ROM and is not one"),
])


def compare(baseline, rows):
    """What differs between two tables of one library: the paths only the new
    one has, the paths only the old one has, the rows whose compared columns
    differ, as (path, [(column, old, new)]), the paths whose detail alone
    differs, and the paths whose fileMd5 alone moved, from a value to none:
    a digest the new build left for whoever asks for it."""
    old = {r["path"]: r for r in baseline}
    new = {r["path"]: r for r in rows}
    added = sorted(p for p in new if p not in old)
    removed = sorted(p for p in old if p not in new)
    changed, detail_only, digest_later = [], [], []
    for path in sorted(p for p in new if p in old):
        moved = [(c, old[path].get(c, ""), new[path].get(c, ""))
                 for c in COMPARED if old[path].get(c, "") != new[path].get(c, "")]
        if len(moved) == 1 and moved[0][0] == "fileMd5" and not moved[0][2]:
            digest_later.append(path)
        elif moved:
            changed.append((path, moved))
        elif old[path].get("detail", "") != new[path].get("detail", ""):
            detail_only.append(path)
    return added, removed, changed, detail_only, digest_later


def state_columns(rows):
    present = {r["state"] for r in rows}
    return [s for s in STATE_ORDER if s in present] + sorted(present - set(STATE_ORDER))


def print_table(rows, roots):
    states = state_columns(rows)
    counts = {}
    for r in rows:
        counts.setdefault(collection_of(r["path"], roots), Counter())[r["state"]] += 1
    width = max([len("collection")] + [len(c) for c in counts]) + 2
    print(f"{'collection':<{width}}{'files':>7}" + "".join(f"{s:>{len(s) + 2}}" for s in states))
    for name in sorted(counts, key=str.lower):
        line = counts[name]
        print(f"{name:<{width}}{sum(line.values()):>7}"
              + "".join(f"{line[s]:>{len(s) + 2}}" for s in states))
    total = Counter(r["state"] for r in rows)
    print(f"{'total':<{width}}{len(rows):>7}" + "".join(f"{total[s]:>{len(s) + 2}}" for s in states))


def print_read(rows, roots, comments):
    """What the scan read, by collection: the files the hasher was handed,
    the bytes it read for them and the bytes they are."""
    for name in ("hasher", "read"):
        for value in said(comments, name):
            print(f"# {name}: {value}")
    sums = {}
    for r in rows:
        if not r.get("read", ""):
            continue
        line = sums.setdefault(collection_of(r["path"], roots), [0, 0, 0])
        line[0] += 1
        line[1] += int(r["read"])
        line[2] += int(r.get("size", "") or 0)
    width = max([len("collection")] + [len(c) for c in sums]) + 2
    print(f"{'collection':<{width}}{'files':>7}{'read':>16}{'size':>16}")
    for name in sorted(sums, key=str.lower):
        print(f"{name:<{width}}{sums[name][0]:>7}{sums[name][1]:>16}{sums[name][2]:>16}")
    total = [sum(line[i] for line in sums.values()) for i in range(3)]
    print(f"{'TOTAL':<{width}}{total[0]:>7}{total[1]:>16}{total[2]:>16}")


def said(comments, name):
    """Every value the `#` lines give for one name, in an order two tables share."""
    return sorted(value for key, value in comments if key == name)


def print_list(lines, everything):
    shown = lines if everything else lines[:LIST_LIMIT]
    for line in shown:
        print("    " + line)
    if len(lines) > len(shown):
        print(f"    ... and {len(lines) - len(shown)} more (--all lists them)")


def main(argv):
    parser = argparse.ArgumentParser(
        prog="audit_report.py", description="Reports on the table an audit wrote.")
    parser.add_argument("table")
    parser.add_argument("--baseline", metavar="TABLE")
    parser.add_argument("--oracle", metavar="ANSWERS")
    parser.add_argument("--min-identified", type=int, metavar="N")
    parser.add_argument("--max-junk", type=int, metavar="N")
    parser.add_argument("--max-changed", type=int, metavar="N")
    parser.add_argument("--all", action="store_true", help="cut no list short")
    try:
        args = parser.parse_args(argv[1:])
    except SystemExit as e:
        return 2 if e.code else 0

    try:
        comments, rows = read_table(args.table)
        told, baseline = read_table(args.baseline) if args.baseline else (None, None)
        oracle = read_oracle(args.oracle) if args.oracle else None
    except Unreadable as e:
        print(e, file=sys.stderr)
        return 2
    if args.max_changed is not None and baseline is None:
        print("--max-changed needs a --baseline to count changes from", file=sys.stderr)
        return 2

    failed = []
    roots = [value for name, value in comments if name == "root"]
    print(f"{args.table}: {len(rows)} files")
    for name, value in comments:
        if name == "stopped":
            print(f"THE SCAN STOPPED EARLY ({value}): the table is of part of the library")
    print()
    print_table(rows, roots)

    not_read = Counter(r["detail"] for r in rows if r.get("detail", "").startswith("audit:"))
    for reason, count in sorted(not_read.items()):
        print(f"{count} of the files above were not read ({reason}) and are among HASH_FAILED")

    def known(row):
        return oracle is not None and 0 < oracle.get(row.get("hash", "").lower(), 0) <= VIRTUAL_ID_BASE

    if any(r.get("read", "") for r in rows):
        print()
        print("bytes read")
        print_read(rows, roots, comments)

    matched = sum(1 for r in rows if r["state"] == "MATCHED")
    identified = sum(1 for r in rows if r["state"] == "MATCHED" or known(r))
    print()
    if oracle is None:
        print(f"identified: {identified}")
    else:
        answered = sum(1 for r in rows if r.get("hash", "").lower() in oracle)
        print(f"identified: {identified} ({matched} matched by the scan, "
              f"{identified - matched} more by the oracle)")
        print(f"recorded answers: {answered} files have a hash that is in the oracle")
    if args.min_identified is not None and identified < args.min_identified:
        failed.append(f"{identified} identified, fewer than {args.min_identified}")

    junk = [(r, junk_rules(r, roots)) for r in rows]
    junk = [(r, rules) for r, rules in junk if rules]
    print()
    print(f"junk hashes: {len(junk)}")
    for rule, means in JUNK_MEANS.items():
        hit = [r for r, rules in junk if rule in rules]
        print(f"  {rule} {len(hit):>5}  {means}")
        print_list([f"{r['path']}  console {r.get('console', '') or '-'}  {r.get('hash', '')}"
                    for r in hit], args.all)
    if args.max_junk is not None and len(junk) > args.max_junk:
        failed.append(f"{len(junk)} junk hashes, more than {args.max_junk}")

    if baseline is not None:
        added, removed, changed, detail_only, digest_later = compare(baseline, rows)
        differing = len(added) + len(removed) + len(changed)
        print()
        print(f"against {args.baseline} ({len(baseline)} files): {differing} differ")
        for why in said(told, "stopped"):
            print(f"  ITS SCAN STOPPED EARLY ({why}): it is the table of part of the library")
        for name in RUN_LINES:
            there, here = said(told, name), said(comments, name)
            if there != here:
                print(f"  the two runs were not made alike, # {name}: "
                      f"{', '.join(there) or 'not given'} there, {', '.join(here) or 'not given'} here")
        # What each build judges a file by. A table of a build from before
        # the line was written has none, which is a difference too.
        there, here = said(told, "recipe"), said(comments, "recipe")
        if there != here:
            print("  the two builds do not judge a file by the same recipe:")
            print(f"    there  {', '.join(there) or 'not given'}")
            print(f"    here   {', '.join(here) or 'not given'}")
        print(f"  only here {len(added):>5}")
        print_list(added, args.all)
        print(f"  only there {len(removed):>4}")
        print_list(removed, args.all)
        print(f"  changed {len(changed):>7}")
        print_list([path + "  " + "; ".join(f"{c}: {a or '-'} -> {b or '-'}" for c, a, b in moved)
                    for path, moved in changed], args.all)
        columns = Counter(c for _, moved in changed for c, _, _ in moved)
        if columns:
            print("  by column: " + ", ".join(f"{c} {columns[c]}" for c in COMPARED if columns[c]))
        print(f"  detail only {len(detail_only):>3}  (not counted as differing)")
        print_list(detail_only, args.all)
        print(f"  digest left for later {len(digest_later):>3}  (not counted as differing)")
        by_collection = Counter(collection_of(p, roots) for p in digest_later)
        if by_collection:
            print("    " + ", ".join(f"{name} {by_collection[name]}"
                                     for name in sorted(by_collection, key=str.lower)))
        print_list(digest_later, args.all)
        if oracle is not None:
            now = {r["path"]: r.get("hash", "").lower() for r in rows}
            recorded = [r for r in baseline if r.get("hash", "").lower() in oracle]
            lost = [r["path"] for r in recorded if now.get(r["path"]) != r["hash"].lower()]
            print(f"  recorded answers: {len(recorded) - len(lost)} of the {len(recorded)} files "
                  "whose hash was in the oracle still have that hash")
            print_list(sorted(lost), args.all)
        if args.max_changed is not None and differing > args.max_changed:
            failed.append(f"{differing} files differ from the baseline, more than {args.max_changed}")

    if failed:
        print()
        for why in failed:
            print(f"FAILED: {why}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
