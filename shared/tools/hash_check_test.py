#!/usr/bin/env python3
"""Tests hash_check.sh with a stand-in for the audit: a `gradlew` written
here, which puts the table the test has ready where the script asked for one
and ends as the test tells it.

    ./tools/hash_check_test.py

The script and audit_report.py are copied into a temporary folder laid out
as shared/ is, beside that `gradlew`, and run from there. So no library is
scanned and no build is made: what is held is what the script does around
the audit, the table it names, the report it asks for, the line it keeps and
the status it ends with. The tables are written as audit_report_test.py
writes its own.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tools/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_report_test as tables  # noqa: E402

TOOLS = Path(__file__).resolve().parent

# Stands for `./gradlew --no-daemon -q :daemon:audit -PauditArgs=...`. It
# keeps what it was called with, a line an argument, and the folder it was
# called in; writes the table in stub/next.tsv to the --out it was given;
# and ends with the status in stub/exit, in the audit's words when that is
# not 0.
GRADLEW = """#!/usr/bin/env bash
stub="$(cd "$(dirname "$0")" && pwd)/stub"
{ pwd; printf '%s\\n' "$@"; } > "$stub/called"
for arg in "$@"; do
    case "$arg" in -PauditArgs=*) audit=${arg#-PauditArgs=} ;; esac
done
out=${audit#*--out=}
out=${out%% --oracle=*}
status=$(cat "$stub/exit")
if [ "$status" -ne 0 ]; then
    echo "audit: not a folder: /nowhere" >&2
    exit "$status"
fi
cp "$stub/next.tsv" "$out"
echo "audit: wrote $out"
"""

ROWS = [
    tables.row("gba/A.gba", hash=tables.md5("a"), size="1000", read="2000"),
    tables.row("gba/B.gba", hash=tables.md5("b"), size="500", read="1000"),
    tables.row("psx/Disc.bin", hash=tables.md5("d"), size="700000000", read="2048"),
]
METRICS = ("metrics", tables.metrics(1000, (100, 50, 800, 50), (3000, 400, 0, 20), 3, bytesRead=5048))
SAID = (("recipe", "rules=6;rc=1"), ("root", tables.ROOT), METRICS)
HEADER = ["table", "date", "commit", "recipe", "files", "filesHashed", "bytesRead", "wallMs", "hashMs",
          "differ", "verdict"]


class HashCheckTest(tables.Tables):

    def setUp(self):
        super().setUp()
        self.shared = self.dir / "checkout" / "shared"
        self.stub = self.shared / "stub"
        self.stub.mkdir(parents=True)
        (self.shared / "tools").mkdir()
        for name in ("hash_check.sh", "audit_report.py"):
            shutil.copy2(TOOLS / name, self.shared / "tools" / name)
        gradlew = self.shared / "gradlew"
        gradlew.write_text(GRADLEW, encoding="utf-8")
        gradlew.chmod(0o755)
        self.keep = self.dir / "kept outside"   # a space, as a folder may well have
        self.audit_writes(ROWS)

    def audit_writes(self, rows, said=SAID, status=0):
        """What the next audit leaves: this table, and this status."""
        shutil.copy2(self.table("next.tsv", rows, comments=said), self.stub / "next.tsv")
        (self.stub / "exit").write_text(str(status), encoding="utf-8")
        called = self.stub / "called"
        if called.exists():
            called.unlink()

    def check(self, *argv, cwd=None):
        done = subprocess.run([str(self.shared / "tools" / "hash_check.sh"), *argv], cwd=cwd or self.dir,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return done.returncode, done.stdout.decode("utf-8"), done.stderr.decode("utf-8")

    def scan(self, *more):
        return self.check("--library", "/library/roms", "--keep-in", str(self.keep), *more)

    def audit_ran(self):
        return (self.stub / "called").exists()

    def history(self):
        """The lines of history.tsv after the one that names the columns, as cells."""
        lines = (self.keep / "history.tsv").read_text(encoding="utf-8").splitlines()
        self.assertEqual(lines[0].split("\t"), HEADER)
        return [line.split("\t") for line in lines[1:]]

    def baseline(self, rows, said=SAID):
        self.keep.mkdir(exist_ok=True)
        shutil.copy2(self.table("before.tsv", rows, comments=said), self.keep / "baseline.tsv")

    def test_a_first_run_keeps_its_table_and_says_there_is_no_baseline(self):
        status, out, err = self.scan()
        self.assertEqual(status, 0, err)
        self.assertIn(f"hash_check: no baseline at {self.keep}/baseline.tsv", out)
        self.assertEqual(out.splitlines()[-2], "VERDICT: no baseline")
        # The time is this run's alone, and printed from the report.
        self.assertIn("\nwall                    1000\n", "\n" + out)

        (line,) = self.history()
        name = line[0]
        self.assertRegex(name, r"^\d{8}T\d{6}Z-\w+(-dirty)?$")
        self.assertEqual(line[1:3], name.split("-", 1))
        # The recipe, the rows, and from the table's own account of the scan
        # the files hashed, the bytes, the time by the clock and in the hasher.
        self.assertEqual(line[3:], ["rules=6;rc=1", "3", "3", "5048", "1000", "3000", "", "no baseline"])
        self.assertEqual(sorted(p.name for p in self.keep.iterdir()),
                         sorted([name + ".tsv", name + ".tsv.report", name + ".log", "history.tsv"]))
        self.assertEqual((self.keep / (name + ".tsv")).read_bytes(), (self.stub / "next.tsv").read_bytes())
        self.assertIn("audit: wrote", (self.keep / (name + ".log")).read_text(encoding="utf-8"))
        self.assertEqual((self.keep / (name + ".tsv.report")).read_text(encoding="utf-8").splitlines()[-1],
                         "VERDICT: no baseline")

    def test_the_audit_is_one_that_asks_nobody_and_is_given_whole_paths(self):
        there = self.dir / "elsewhere"
        there.mkdir()
        shutil.copy2(self.oracle({tables.md5("a"): 1}), there / "answers.tsv")
        status, _, err = self.check("--library", "my roms|/other/roms", "--keep-in", "kept", "--oracle", "answers.tsv",
                                    cwd=there)
        self.assertEqual(status, 0, err)
        called = (self.stub / "called").read_text(encoding="utf-8").splitlines()
        # Run in shared/, which is where the audit starts its paths from.
        self.assertEqual(called[0], str(self.shared.resolve()))
        self.assertEqual(called[1:4], ["--no-daemon", "-q", ":daemon:audit"])
        (audit,) = called[4:]
        name = (there / "kept" / "history.tsv").read_text(encoding="utf-8").splitlines()[1].split("\t")[0]
        self.assertEqual(audit, f"-PauditArgs=--audit={there}/my roms|/other/roms "
                                f"--out={there}/kept/{name}.tsv --oracle={there}/answers.tsv")
        # Nothing in it has the audit ask RetroAchievements.
        self.assertNotIn("--lookup", audit)

    def test_a_run_is_held_against_the_baseline_and_ends_with_the_reports_status(self):
        self.baseline(ROWS)
        status, out, err = self.scan()
        self.assertEqual(status, 0, err)
        self.assertEqual(out.splitlines()[-2], "VERDICT: same as the baseline")
        self.assertEqual(self.history()[-1][-2:], ["0", "same as the baseline"])
        # Both tables say where the time went, so the two are side by side.
        self.assertRegex(out, r"(?m)^wall +1000 +1000 +1\.00$")

        # A file whose state moved: one differs, which none may unless
        # --allow says so.
        moved = [dict(r) for r in ROWS]
        moved[0].update(state="HASH_FAILED", hash="", asked="0")
        self.audit_writes(moved)
        status, out, _ = self.scan()
        self.assertEqual((status, out.splitlines()[-2]), (1, "VERDICT: 1 files differ from the baseline"))
        self.assertEqual(self.history()[-1][-2:], ["1", "1 files differ from the baseline"])
        status, out, _ = self.scan("--allow", "1")
        self.assertEqual((status, out.splitlines()[-2]), (0, "VERDICT: 1 files differ from the baseline"))

        # A library that gained a file differs by it, and what it read is
        # not held against a baseline that never had it.
        grown = ROWS + [tables.row("gba/New.gba", hash=tables.md5("n"), size="9", read="900000")]
        self.audit_writes(grown)
        status, out, _ = self.scan()
        self.assertEqual((status, out.splitlines()[-2]), (1, "VERDICT: 1 files differ from the baseline"))
        self.assertEqual(self.history()[-1][4], "4")
        self.assertEqual(self.scan("--allow", "1")[0], 0)

        # A file read whole where its start was enough: no file differs,
        # and a tenth more read is the limit.
        twice = [dict(r) for r in ROWS]
        twice[2]["read"] = "700000000"
        self.audit_writes(twice)
        status, out, _ = self.scan()
        self.assertEqual(status, 1)
        self.assertEqual(out.splitlines()[-2], "VERDICT: failed: 700003000 bytes read where the baseline "
                                               "read 5048, more than 10% above it")
        self.assertEqual(self.history()[-1][-2][:1], "0")
        self.assertTrue(self.history()[-1][-1].startswith("failed: 700003000 bytes read"))
        self.assertEqual(len(self.history()), 6)

    def test_a_baseline_from_before_the_metrics_is_judged_by_its_rows_and_what_they_read(self):
        self.baseline(ROWS, said=(("root", tables.ROOT),))
        status, out, err = self.scan()
        self.assertEqual(status, 0, err)
        self.assertEqual(out.splitlines()[-2], "VERDICT: same as the baseline")
        # No time there to set this run's beside.
        self.assertRegex(out, r"(?m)^wall +1000$")
        report = next(self.keep.glob("*.report")).read_text(encoding="utf-8")
        self.assertIn("read for the 3 files hashed in both at one size: 5048 bytes there, 5048 here", report)
        # The table itself from before the line: the history has no time to give.
        self.audit_writes(ROWS, said=(("root", tables.ROOT),))
        status, out, err = self.scan()
        self.assertEqual(status, 0, err)
        self.assertNotIn("time, in ms", out)
        self.assertEqual(self.history()[-1][3:], ["", "3", "", "", "", "", "0", "same as the baseline"])

    def test_accept_makes_a_table_the_baseline(self):
        # Given alone before any run: nothing to accept.
        status, _, err = self.check("--keep-in", str(self.keep), "--accept")
        self.assertEqual(status, 2)
        self.assertIn("no run in", err)

        # Given alone after one: that run's table, and no audit made for it.
        self.assertEqual(self.scan()[0], 0)
        self.audit_writes(ROWS)
        status, out, err = self.check("--keep-in", str(self.keep), "--accept")
        self.assertEqual(status, 0, err)
        self.assertFalse(self.audit_ran())
        name = self.history()[-1][0]
        self.assertIn(f"the baseline is now {self.keep}/{name}.tsv", out)
        self.assertEqual((self.keep / "baseline.tsv").read_bytes(), (self.keep / (name + ".tsv")).read_bytes())

        # Given with a library: this run's, after it was held against the
        # one before, and also when that found it differing.
        moved = [dict(r) for r in ROWS]
        moved[1]["hash"] = tables.md5("another")
        self.audit_writes(moved)
        status, out, _ = self.scan("--accept")
        self.assertEqual(status, 1)
        self.assertIn("VERDICT: 1 files differ from the baseline", out)
        self.assertEqual((self.keep / "baseline.tsv").read_bytes(), (self.stub / "next.tsv").read_bytes())
        status, out, _ = self.scan()
        self.assertEqual((status, out.splitlines()[-2]), (0, "VERDICT: same as the baseline"))

    def test_an_audit_that_failed_is_status_2_and_leaves_no_line_and_no_baseline(self):
        self.baseline(ROWS)
        before = (self.keep / "baseline.tsv").read_bytes()
        self.audit_writes(ROWS, status=1)
        status, out, err = self.scan("--accept")
        self.assertEqual(status, 2)
        # The audit's own words, and where the rest of them are.
        self.assertIn("audit: not a folder: /nowhere", err)
        self.assertIn("the audit did not end as a finished scan does", err)
        self.assertNotIn("VERDICT", out)
        self.assertFalse((self.keep / "history.tsv").exists())
        self.assertEqual((self.keep / "baseline.tsv").read_bytes(), before)
        self.assertEqual([p.suffix for p in self.keep.iterdir() if p.name != "baseline.tsv"], [".log"])

    def test_what_cannot_be_a_run_is_refused_before_the_library_is_scanned(self):
        for argv, words in ((("--library", "/library/roms"), "give --keep-in"),
                            (("--keep-in", str(self.keep)), "give --library"),
                            (("--keep-in",), "--keep-in takes a value"),
                            (("--keep-in", str(self.keep), "--library", "/library/roms", "--lookup"),
                             "unknown argument --lookup"),
                            # A scan of a library takes hours. A baseline's name
                            # mistyped must not end it as a first run ends, with
                            # no baseline and status 0, nor a limit that is no
                            # number end it with a table and no report.
                            (("--keep-in", str(self.keep), "--library", "/library/roms",
                              "--baseline", str(self.dir / "no-such-table.tsv")), "no table at"),
                            (("--keep-in", str(self.keep), "--library", "/library/roms", "--allow", "a few"),
                             "--allow takes a number"),
                            (("--keep-in", str(self.keep), "--library", "/library/roms", "--allow", "-1"),
                             "--allow takes a number")):
            with self.subTest(argv=argv):
                status, out, err = self.check(*argv)
                self.assertEqual((status, out), (2, ""), err)
                self.assertIn(words, err)
                self.assertFalse(self.audit_ran())
                self.assertFalse((self.keep / "history.tsv").exists())

    def test_a_folder_inside_the_repository_is_no_place_to_keep_the_tables(self):
        # A table names every file of the library, and one kept in the
        # checkout is a `git add` away from being committed.
        checkout = self.shared.parent
        subprocess.run(["git", "init", "-q", str(checkout)], check=True, stdout=subprocess.DEVNULL)
        for inside in (checkout / "kept", self.shared / "build" / "kept"):
            status, out, err = self.check("--library", "/library/roms", "--keep-in", str(inside))
            self.assertEqual((status, out), (2, ""), err)
            self.assertIn("is inside the repository", err)
            self.assertFalse(self.audit_ran())
            self.assertEqual(list(inside.iterdir()), [])
            # Nor is a table looked for there to be made the baseline.
            status, _, err = self.check("--keep-in", str(inside), "--accept")
            self.assertEqual(status, 2)
            self.assertIn("is inside the repository", err)
        # Beside the checkout is outside it.
        status, out, err = self.check("--library", "/library/roms", "--keep-in", str(self.dir / "checkout-tables"))
        self.assertEqual(status, 0, err)
        self.assertEqual(out.splitlines()[-2], "VERDICT: no baseline")

    def test_a_baseline_named_is_the_one_a_run_is_held_against(self):
        other = [dict(r) for r in ROWS]
        other[0]["hash"] = tables.md5("as it was before")
        named = self.table("named.tsv", other, comments=SAID)
        self.baseline(ROWS)
        status, out, _ = self.scan("--baseline", named)
        self.assertEqual((status, out.splitlines()[-2]), (1, "VERDICT: 1 files differ from the baseline"))
        self.assertIn(f"against {named} (3 files): 1 differ",
                      next(self.keep.glob("*.report")).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
