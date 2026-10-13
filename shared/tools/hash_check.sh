#!/usr/bin/env bash
# Audits a library with this build and says in one line whether its hashes
# are the ones the table before it has.
#
#     ./tools/hash_check.sh --library '<root>[|<root>...]' --keep-in <dir>
#                           [--baseline <table>] [--allow N] [--oracle <tsv>]
#                           [--accept]
#     ./tools/hash_check.sh --keep-in <dir> --accept
#
# It is the two commands a change to hashing was judged with, made one: the
# audit (`./gradlew :daemon:audit`, which asks nobody: there is no --lookup
# here) and tools/audit_report.py against the baseline, with no more than
# --allow files differing, none unless it is given, and no more than a tenth
# more read for the files both tables have. It prints where the scan's time
# went and the report's last line, the VERDICT, and ends with the report's
# status: 0, 1 when a limit was broken, 2 when there is no table to judge.
#
# Everything it writes goes to <dir>, which is to be OUTSIDE the repository:
# a table names every file of the library it is of. There it keeps
#
#     <date>-<commit>[-dirty].tsv      the table of each run, and its .report
#     baseline.tsv                     the table the next run is held against
#     history.tsv                      a line a run: date, commit, recipe,
#                                      files, files hashed, bytes read, wall
#                                      ms, hash ms, files differing, verdict
#
# With no baseline the run writes its table and says so. --accept makes a
# table the baseline: this run's when a library is given, and only if its scan
# ended and the report could read it; with no library, the table of the last
# run in the history, so that a scan of hours is not made twice to be kept.
# The history is the benchmark: the same library on the same machine, a line
# for each build it was scanned with. Time is in it to be read, not judged.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
library= keep= baseline= allow=0 oracle= accept=
while [ $# -gt 0 ]; do
    case "$1" in
        --library|--keep-in|--baseline|--allow|--oracle)
            [ $# -ge 2 ] || { echo "hash_check: $1 takes a value" >&2; exit 2; }
            case "$1" in
                --library) library=$2 ;; --keep-in) keep=$2 ;; --baseline) baseline=$2 ;;
                --allow) allow=$2 ;; --oracle) oracle=$2 ;;
            esac
            shift 2 ;;
        --accept) accept=1; shift ;;
        *) echo "hash_check: unknown argument $1" >&2; exit 2 ;;
    esac
done
[ -n "$keep" ] || { echo "hash_check: give --keep-in <dir>, a folder outside the repository" >&2; exit 2; }
mkdir -p "$keep"
keep=$(cd "$keep" && pwd)
history="$keep/history.tsv"

if [ -z "$library" ]; then
    [ -n "$accept" ] || { echo "hash_check: give --library '<root>[|<root>...]'" >&2; exit 2; }
    [ -f "$history" ] || { echo "hash_check: no run in $history to accept" >&2; exit 2; }
    last=$(tail -n 1 "$history" | cut -f 2,3 | tr '\t' ' ')
    table="$keep/$(tail -n 1 "$history" | cut -f 1).tsv"
    [ -f "$table" ] || { echo "hash_check: no run in $history to accept" >&2; exit 2; }
    cp "$table" "$keep/baseline.tsv"
    echo "hash_check: the baseline is now $table ($last)"
    exit 0
fi

# The audit starts from shared/, so a path that is not absolute is made one
# from where the caller stands.
absolute() { case "$1" in /*|[A-Za-z]:*) printf %s "$1" ;; *) printf %s "$PWD/$1" ;; esac; }
roots=
IFS='|' read -r -a given <<< "$library"
for root in "${given[@]}"; do roots="${roots:+$roots|}$(absolute "$root")"; done
[ -z "$oracle" ] || oracle=$(absolute "$oracle")
[ -n "$baseline" ] || baseline="$keep/baseline.tsv"

commit=$(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo unknown)
[ -z "$(git -C "$here" status --porcelain)" ] || commit="$commit-dirty"
name="$(date -u +%Y%m%dT%H%M%SZ)-$commit"
table="$keep/$name.tsv"

if ! (cd "$here/.." && ./gradlew --no-daemon -q :daemon:audit \
        -PauditArgs="--audit=$roots --out=$table${oracle:+ --oracle=$oracle}") > "$keep/$name.log" 2>&1; then
    # The audit's own words where it had any; a build that failed has none.
    grep '^audit: ' "$keep/$name.log" >&2 || tail -n 20 "$keep/$name.log" >&2
    echo "hash_check: the audit did not end as a finished scan does; see $keep/$name.log" >&2
    exit 2
fi

compared=()
if [ -f "$baseline" ]; then
    compared=(--baseline "$baseline" --max-changed "$allow" --max-read-growth 10)
else
    echo "hash_check: no baseline at $baseline; --accept makes this run's table the one"
fi
status=0
"$here/audit_report.py" "$table" "${compared[@]}" ${oracle:+--oracle "$oracle"} > "$table.report" || status=$?
[ "$status" -le 1 ] || { echo "hash_check: the report could not read $table" >&2; exit 2; }

# The history's line, from the table's own `#` lines and the report's count.
python3 - "$here" "$table" "$history" "$name" <<'EOF'
import sys
sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
import audit_report as report
table, history, name = sys.argv[2:]
comments, rows = report.read_table(table)
metrics = report.metrics_of(comments, table) or {}
lines = open(table + ".report", encoding="utf-8").read().splitlines()
differ = next((l.rpartition(": ")[2].split()[0] for l in lines if l.startswith("against ")), "")
date, _, commit = name.partition("-")
cells = [name, date, commit, ", ".join(report.said(comments, "recipe")), len(rows),
         metrics.get("filesHashed", ""), metrics.get("bytesRead", ""), metrics.get("wallMs", ""),
         metrics.get("busyMs", {}).get("hash", ""), differ, lines[-1].partition(": ")[2]]
with open(history, "a", encoding="utf-8") as f:
    if f.tell() == 0:
        f.write("table\tdate\tcommit\trecipe\tfiles\tfilesHashed\tbytesRead\twallMs\thashMs\tdiffer\tverdict\n")
    f.write("\t".join(str(c) for c in cells) + "\n")
EOF

awk '/^time, in ms/ { on = 1 } on && /^$/ { exit } on' "$table.report"
tail -n 1 "$table.report"
echo "hash_check: the whole report is $table.report"
if [ -n "$accept" ]; then
    cp "$table" "$keep/baseline.tsv"
    echo "hash_check: the baseline is now $table"
fi
exit "$status"
