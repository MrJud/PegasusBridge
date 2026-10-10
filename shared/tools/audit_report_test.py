#!/usr/bin/env python3
"""Tests audit_report.py on small tables written to a temporary directory: one
for each junk rule, with the rows nearest to it that are not junk, and pairs
of tables for what a comparison has to see and what it has to pass over.

    ./tools/audit_report_test.py

The tables are shaped like the ones ScanAudit writes: lines beginning `#`, a
line that names the columns, then a row for each file with tabs between the
cells and a backslash before a tab, a line break or a backslash inside one.
"""

import hashlib
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tools/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_report as report  # noqa: E402

ROOT = "/library/roms"


def md5(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def row(path, state="NOT_FOUND", **cells):
    """A row as a dict, for a file under ROOT. The platform is the folder the
    file is in and the extension the file's own unless the test says otherwise,
    and a row with a hash was asked about."""
    full = f"{ROOT}/{path}"
    names = path.split("/")
    out = dict.fromkeys(report.COLUMNS, "")
    out.update(path=full, state=state, size="100", ms="5",
               platform=names[-2] if len(names) > 1 else "roms",
               extension=names[-1].rpartition(".")[2].lower())
    out.update(cells)
    if out["hash"] and "asked" not in cells:
        out["asked"] = "1"
    elif not out["asked"]:
        out["asked"] = "0"
    return out


def escape(cell):
    return cell.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


class Tables(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def table(self, name, rows, comments=(("root", ROOT),), columns=report.COLUMNS):
        lines = [f"# {key}\t{escape(value)}" for key, value in comments]
        lines.append("\t".join(columns))
        lines += ["\t".join(escape(r.get(c, "")) for c in columns) for r in rows]
        path = self.dir / name
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return str(path)

    def oracle(self, answers):
        path = self.dir / "answers.tsv"
        path.write_text("# recorded for a test\nhash\tgameId\tdate\tsource\ttitle\n\n"
                        + "".join(f"{h}\t{g}\t2026-10-09\ta test\n" for h, g in answers.items()),
                        encoding="utf-8")
        return str(path)

    def run_report(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            status = report.main(["audit_report.py", *argv])
        return status, out.getvalue(), err.getvalue()

    def junk_line(self, out, rule):
        """The count on the line of one rule, and the paths listed under it."""
        lines = out.splitlines()
        at = next(i for i, line in enumerate(lines) if line.startswith(f"  {rule} "))
        listed = []
        for line in lines[at + 1:]:
            if not line.startswith("    "):
                break
            listed.append(line.strip().split("  ")[0])
        return int(lines[at].split()[1]), listed


class JunkTest(Tables):

    def only(self, rule, rows, junk):
        """`rows` break `rule` in the files `junk` names, and no other rule at all."""
        status, out, _ = self.run_report(self.table("t.tsv", rows), "--all")
        self.assertEqual(status, 0)
        for other in report.JUNK_MEANS:
            count, listed = self.junk_line(out, other)
            expected = [f"{ROOT}/{p}" for p in junk] if other == rule else []
            self.assertEqual(sorted(listed), sorted(expected), f"{other}\n{out}")
            self.assertEqual(count, len(expected), f"{other}\n{out}")
        self.assertIn(f"junk hashes: {len(junk)}\n", out)

    def test_j1_a_descriptor_hashed_as_its_own_text(self):
        text = md5("FILE track01.bin BINARY")
        rows = [
            row("psx/Game.cue", hash=text, fileMd5=text, console="12"),
            # Capitals in the extension or in either hash are the same file.
            row("dreamcast/Game.GDI", hash=text.upper(), fileMd5=text, console="40", extension="GDI"),
            row("psx/Set.m3u", hash=text, fileMd5=text, console="12"),
            row("saturn/Game.ccd", hash=text, fileMd5=text.upper(), console="39"),
            row("psx/Game.toc", hash=text, fileMd5=text, console="12"),
            # The entry's extension is the one that counts in an archive.
            row("psx/Packed.zip", hash=text, fileMd5=text, console="12", archiveEntry="Packed.cue"),
            # A descriptor hashed as it should be: the hash is of its tracks.
            row("psx/Right.cue", hash=md5("the tracks"), fileMd5=text, console="12"),
            # A whole-file hash that is right: it is what a cartridge gets.
            row("megadrive/Cart.md", hash=text, fileMd5=text, console="1"),
            row("psx/Unhashed.cue", state="HASH_FAILED", fileMd5=""),
            row("megadrive/Other.zip", hash=text, fileMd5=text, console="1", archiveEntry="Other.md"),
        ]
        self.only("J1", rows, ["psx/Game.cue", "dreamcast/Game.GDI", "psx/Set.m3u",
                               "saturn/Game.ccd", "psx/Game.toc", "psx/Packed.zip"])

    def test_j2_a_file_hashed_by_its_name_outside_an_arcade_collection(self):
        rows = [
            row("megadrive/alpha.zip", hash=md5("alpha").upper(), console="27"),
            # FBNeo's console folders go into the hash with the name, in
            # small letters however the folder is written.
            row("megadrive/nes/beta.zip", hash=md5("nes_beta"), console="27"),
            row("megadrive/NES/lambda.zip", hash=md5("nes_lambda"), console="27"),
            row("snes/Two.Dots.v1.zip", hash=md5("Two.Dots.v1"), console="27"),
            # A folder inside one the scan takes for an arcade collection is
            # not one itself, and neither it nor the folder at the root is.
            row("pack/bios/mame/artwork/kappa.zip", hash=md5("kappa"), console="27"),
            # An arcade collection, by each of its names: this is its algorithm.
            row("arcade/gamma.zip", hash=md5("gamma"), console="27"),
            row("atomiswave/delta.zip", hash=md5("delta"), console="27"),
            row("anything/epsilon.zip", hash=md5("epsilon"), console="27", platform="Neo-Geo"),
            row("anything/zeta.zip", hash=md5("zeta"), console="27", platform="FBNeo"),
            row("anything/eta.zip", hash=md5("eta"), console="27", platform="cps1"),
            # In an arcade collection by the folder at the root alone: the scan
            # handed this one over as "sets".
            row("arcade/sets/theta.zip", hash=md5("theta"), console="27"),
            # And by what the scan took it for alone: a folder called mame is
            # hashed by name on purpose, wherever it is.
            row("pack/bios/mame/iota.zip", hash=md5("iota"), console="27"),
            # Some other hash, and the name's with the extension left on.
            row("megadrive/real.zip", hash=md5("the rom"), console="1", archiveEntry="real.md"),
            row("megadrive/whole.zip", hash=md5("whole.zip"), console="1"),
            # With no extension there is no name without it: the MD5 of no
            # text is what an empty file hashes to whole, and that of the
            # whole name is not what rcheevos makes of such a file.
            row("megadrive/Empty", hash=md5(""), console="1", extension=""),
            row("megadrive/Whole", hash=md5("Whole"), console="1", extension=""),
        ]
        self.only("J2", rows, ["megadrive/alpha.zip", "megadrive/nes/beta.zip", "megadrive/NES/lambda.zip",
                               "snes/Two.Dots.v1.zip", "pack/bios/mame/artwork/kappa.zip"])
        # A name alone has no folder to go into the hash with it.
        self.assertEqual(report.arcade_hashes("alpha.zip"), {md5("alpha")})

    def test_j3_a_hash_in_a_collection_that_has_no_algorithm(self):
        nowhere = sorted(report.NO_ALGORITHM)
        self.assertEqual(nowhere, ["3ds", "amiga", "android", "bbcmicro", "cdi", "cdimono1", "cdtv", "chailove",
                                   "ios", "n3ds", "pc", "ps3", "psvita", "switch", "vita", "wiiu", "windows"])
        rows = [row(f"{name}/Game.rom", hash=md5(name), console="1") for name in nowhere]
        rows += [
            # The folder's spelling is the collection's own business.
            row("Wii U/Game.rom", hash=md5("u"), console="1"),
            row("PS-Vita/Game.rom", hash=md5("v"), console="1"),
            # A game in a folder of its own: the scan hands it over under
            # that folder's name, and the collection is the one at the root.
            row("switch/Game/Game.iso", hash=md5("s"), console="1"),
            row("ps3/Some Game/USRDIR/disc.iso", hash=md5("p"), console="1"),
            # And a collection only the scan knows by such a name.
            row("consoles/Handheld.rom", hash=md5("h"), console="1", platform="PS Vita"),
            # Or by the name of the folder it is kept in, under a short name
            # of its own and below the folder at the root.
            row("consoles/psvita/Kept.rom", hash=md5("k"), console="1", platform="handheld", dirName="psvita"),
            row("consoles/portable/Fine.rom", hash=md5("f"), console="1", platform="handheld", dirName="portable"),
            # No hash is what such a collection should have.
            row("switch/Unhashed.nsp", state="UNSUPPORTED", platform=""),
            row("amiga/Unread.adf", state="HASH_FAILED"),
            row("wii/Game.iso", hash=md5("wii"), console="19"),
            # A name that only begins as one of them does, at the root or below.
            row("switch hits/Game.rom", hash=md5("t"), console="1"),
            row("snes/Switch Hits/Game.sfc", hash=md5("w"), console="3"),
        ]
        self.only("J3", rows, sorted([f"{name}/Game.rom" for name in nowhere]
                                     + ["Wii U/Game.rom", "PS-Vita/Game.rom", "switch/Game/Game.iso",
                                        "ps3/Some Game/USRDIR/disc.iso", "consoles/Handheld.rom",
                                        "consoles/psvita/Kept.rom"]))

    def test_j4_a_game_boy_hash_for_a_file_that_is_not_one(self):
        rows = [
            row("mastersystem/Game.sms", hash=md5("a"), console="4"),
            row("atarijaguar/Game.j64", hash=md5("b"), console="4"),
            row("gb/Wrong.zip", hash=md5("c"), console="4", archiveEntry="Wrong.txt"),
            # An entry with no extension has none, whatever it is called.
            row("gb/Bare.zip", hash=md5("j"), console="4", archiveEntry="gb"),
            row("megadrive/NoExtension", hash=md5("d"), console="4", extension=""),
            row("gb/Game.gb", hash=md5("e"), console="4"),
            row("gb/Game.GBC", hash=md5("f"), console="4", extension="GBC"),
            row("gb/Packed.zip", hash=md5("g"), console="4", archiveEntry="Packed.GB"),
            # 40 is Dreamcast and 14 the Neo Geo Pocket: only the number 4 is Game Boy.
            row("dreamcast/Game.cdi", hash=md5("h"), console="40"),
            row("ngp/Game.ngp", hash=md5("i"), console="14"),
            row("mastersystem/Unhashed.sms", state="HASH_FAILED", console=""),
        ]
        self.only("J4", rows, ["atarijaguar/Game.j64", "gb/Bare.zip", "gb/Wrong.zip", "mastersystem/Game.sms",
                               "megadrive/NoExtension"])

    def test_a_file_that_breaks_two_rules_is_one_junk_hash(self):
        rows = [row("amiga/Disk.adf", hash=md5("x"), console="4"),
                row("snes/Fine.sfc", hash=md5("y"), console="3")]
        table = self.table("t.tsv", rows)
        status, out, _ = self.run_report(table, "--max-junk", "1")
        self.assertEqual(status, 0, out)
        self.assertIn("junk hashes: 1\n", out)
        self.assertEqual(self.junk_line(out, "J3")[0], 1)
        self.assertEqual(self.junk_line(out, "J4")[0], 1)

        status, out, _ = self.run_report(table, "--max-junk", "0")
        self.assertEqual(status, 1)
        self.assertIn("FAILED: 1 junk hashes, more than 0", out)


class CompareTest(Tables):

    BEFORE = [
        row("snes/A.sfc", state="MATCHED", hash=md5("a"), fileMd5=md5("fa"), console="3"),
        row("snes/B.zip", hash=md5("b"), fileMd5=md5("fb"), console="3", archiveEntry="B.sfc"),
        row("snes/C.sfc", state="HASH_FAILED", detail="the hasher could not read C.sfc"),
    ]

    def changed(self, **cells):
        after = [dict(r) for r in self.BEFORE]
        after[1].update(cells)
        return after

    def compare(self, after, *more, before=None):
        return self.run_report(self.table("after.tsv", after), "--baseline",
                               self.table("before.tsv", before or self.BEFORE), *more)

    def test_two_runs_that_differ_only_in_time_are_the_same(self):
        after = [dict(r, ms=str(900 + i)) for i, r in enumerate(self.BEFORE)]
        status, out, _ = self.compare(after, "--max-changed", "0")
        self.assertEqual(status, 0, out)
        self.assertIn("(3 files): 0 differ\n", out)
        self.assertIn("  changed       0\n", out)
        self.assertIn("  detail only   0", out)

    def test_the_lines_that_are_not_rows_are_not_compared(self):
        after = self.table("after.tsv", self.BEFORE,
                           comments=(("root", ROOT), ("written", "1791500000"), ("lookups", "9 for 9 hashes")))
        before = self.table("before.tsv", self.BEFORE, comments=(("root", ROOT), ("written", "1791400000")))
        status, out, _ = self.run_report(after, "--baseline", before, "--max-changed", "0")
        self.assertEqual(status, 0, out)
        self.assertIn("(3 files): 0 differ\n", out)
        self.assertNotIn("not made alike", out)
        self.assertNotIn("STOPPED EARLY", out)

    # A changed row is put down to the build unless something says the two
    # runs were not the same run: other folders, another limit, other
    # answers, or a baseline that never got to its end.
    def test_two_runs_that_were_not_made_alike_are_said_to_be(self):
        run = {"root": ROOT, "skip-larger-than": "1000", "skip": f"{ROOT}/bios", "answers": "56 recorded"}
        other = {"root": "/library/other", "skip-larger-than": "2000", "skip": f"{ROOT}/pack",
                 "answers": "retroachievements"}
        self.assertEqual(sorted(run), sorted(report.RUN_LINES))
        before = self.table("before.tsv", self.BEFORE, comments=tuple(run.items()))
        for name, value in other.items():
            with self.subTest(line=name):
                after = self.table("after.tsv", self.BEFORE, comments=tuple(dict(run, **{name: value}).items()))
                status, out, _ = self.run_report(after, "--baseline", before, "--max-changed", "0")
                # Said, and not a failure: the rows are what a limit counts.
                self.assertEqual(status, 0, out)
                self.assertIn(f"  the two runs were not made alike, # {name}: {run[name]} there, {value} here\n", out)
                self.assertEqual(out.count("not made alike"), 1, out)

    # What a build judges a file by is its recipe, the first line of its
    # table. Two tables that do not share it are of two builds that may
    # answer differently for one file, and the comparison says so and prints
    # both, where it used to print neither: the rows that changed were all
    # there was to tell that the builds differ.
    def test_two_builds_of_another_recipe_are_said_to_be(self):
        old = "rules=5;rc=12.5.0+pb6;sel=5dbc3d88"
        new = "rules=6;rc=12.5.0+pb6;sel=5dbc3d88"
        before = self.table("before.tsv", self.BEFORE, comments=(("recipe", old), ("root", ROOT)))
        after = self.table("after.tsv", self.BEFORE, comments=(("recipe", new), ("root", ROOT)))
        status, out, _ = self.run_report(after, "--baseline", before, "--max-changed", "0")
        self.assertEqual(status, 0, out)
        self.assertIn("  the two builds do not judge a file by the same recipe:\n"
                      f"    there  {old}\n    here   {new}\n", out)
        self.assertNotIn("not made alike", out)

        _, out, _ = self.run_report(before, "--baseline", before)
        self.assertNotIn("recipe", out)
        # A table from before the line was written has none.
        _, out, _ = self.run_report(after, "--baseline", self.table("bare.tsv", self.BEFORE))
        self.assertIn(f"    there  not given\n    here   {new}\n", out)

    def test_a_line_one_run_has_more_of_is_a_difference_and_their_order_is_not(self):
        two = (("root", ROOT), ("root", "/library/more"), ("skip-larger-than", "1000"))
        before = self.table("before.tsv", self.BEFORE, comments=two)
        _, out, _ = self.run_report(self.table("same.tsv", self.BEFORE, comments=two[::-1]), "--baseline", before)
        self.assertNotIn("not made alike", out)
        _, out, _ = self.run_report(self.table("bare.tsv", self.BEFORE), "--baseline", before)
        self.assertIn(f"  the two runs were not made alike, # root: /library/more, {ROOT} there, {ROOT} here\n", out)
        self.assertIn("  the two runs were not made alike, # skip-larger-than: 1000 there, not given here\n", out)
        _, out, _ = self.run_report(before, "--baseline", self.table("bare.tsv", self.BEFORE))
        self.assertIn(f"  the two runs were not made alike, # root: {ROOT} there, /library/more, {ROOT} here\n", out)
        self.assertIn("  the two runs were not made alike, # skip-larger-than: not given there, 1000 here\n", out)

    def test_a_baseline_whose_scan_stopped_early_is_said_to_be_one(self):
        before = self.table("before.tsv", self.BEFORE,
                            comments=(("root", ROOT), ("stopped", "no internet connection (1 of 3 processed)")))
        status, out, _ = self.run_report(self.table("after.tsv", self.BEFORE), "--baseline", before)
        self.assertEqual(status, 0, out)
        lines = out.splitlines()
        at = next(i for i, line in enumerate(lines) if line.startswith("against "))
        self.assertEqual(lines[at + 1], "  ITS SCAN STOPPED EARLY (no internet connection (1 of 3 processed)): "
                                        "it is the table of part of the library")
        # The table being reported on did not stop, and is not said to have.
        self.assertNotIn("THE SCAN STOPPED EARLY", out)

    def test_every_column_that_says_what_the_scan_decided_is_compared(self):
        moves = {"platform": "nes", "state": "MATCHED", "console": "7", "hash": md5("other"),
                 "fileMd5": md5("other file"), "archiveEntry": "B.smc", "asked": "0"}
        self.assertEqual(sorted(moves), sorted(report.COMPARED))
        for column, value in moves.items():
            with self.subTest(column=column):
                status, out, _ = self.compare(self.changed(**{column: value}), "--max-changed", "0")
                self.assertEqual(status, 1, out)
                self.assertIn("(3 files): 1 differ\n", out)
                self.assertIn(f"    {ROOT}/snes/B.zip  {column}: {self.BEFORE[1][column]} -> {value}\n", out)
                self.assertIn(f"  by column: {column} 1\n", out)
                self.assertIn("FAILED: 1 files differ from the baseline, more than 0", out)

    def test_size_is_not_compared(self):
        status, out, _ = self.compare(self.changed(size="101"), "--max-changed", "0")
        self.assertEqual(status, 0, out)

    def test_a_reason_reworded_is_listed_apart_and_is_not_a_change(self):
        after = [dict(r) for r in self.BEFORE]
        after[2]["detail"] = "Could not open C.sfc"
        status, out, _ = self.compare(after, "--max-changed", "0")
        self.assertEqual(status, 0, out)
        self.assertIn("(3 files): 0 differ\n", out)
        self.assertIn(f"  detail only   1  (not counted as differing)\n    {ROOT}/snes/C.sfc\n", out)

    def test_a_digest_left_for_later_is_listed_apart_and_is_not_a_change(self):
        before = [row("psx/Disc.bin", state="MATCHED", hash=md5("d"), console="12", fileMd5=md5("disc")),
                  row("nds/Game.nds", hash=md5("g"), console="18", fileMd5=md5("game")),
                  row("gba/Whole.gba", hash=md5("w"), console="5", fileMd5=md5("w"))]
        after = [dict(r) for r in before]
        after[0]["fileMd5"] = after[1]["fileMd5"] = ""
        status, out, _ = self.compare(after, "--max-changed", "0", before=before)
        self.assertEqual(status, 0, out)
        self.assertIn("(3 files): 0 differ\n", out)
        self.assertIn("  changed       0\n", out)
        self.assertIn(f"  digest left for later   2  (not counted as differing)\n    nds 1, psx 1\n"
                      f"    {ROOT}/nds/Game.nds\n    {ROOT}/psx/Disc.bin\n", out)

        # A digest that is another one is a file read differently, a digest
        # that appears is not this, and one that goes with something else
        # moving is that file's change.
        for cells in (dict(fileMd5=md5("another")), dict(fileMd5="", state="NOT_FOUND")):
            moved = [dict(r) for r in before]
            moved[0].update(cells)
            status, out, _ = self.compare(moved, "--max-changed", "0", before=before)
            self.assertEqual(status, 1, out)
            self.assertIn("  changed       1\n", out)
            self.assertIn("  digest left for later   0  (not counted as differing)\n", out)
        status, out, _ = self.compare(before, "--max-changed", "0", before=after)
        self.assertEqual(status, 1, out)
        self.assertIn("  changed       2\n", out)

    def test_a_file_in_one_table_only_is_a_difference(self):
        after = self.BEFORE[1:] + [row("snes/New.sfc", hash=md5("n"), console="3")]
        status, out, _ = self.compare(after, "--max-changed", "1")
        self.assertEqual(status, 1, out)
        self.assertIn("(3 files): 2 differ\n", out)
        self.assertIn(f"  only here     1\n    {ROOT}/snes/New.sfc\n", out)
        self.assertIn(f"  only there    1\n    {ROOT}/snes/A.sfc\n", out)
        self.assertEqual(self.compare(after, "--max-changed", "2")[0], 0)
        # With no limit given the differences are said and nothing fails.
        self.assertEqual(self.compare(after)[0], 0)

    def test_a_limit_on_changes_needs_something_to_count_them_from(self):
        status, _, err = self.run_report(self.table("t.tsv", self.BEFORE), "--max-changed", "0")
        self.assertEqual(status, 2)
        self.assertIn("--baseline", err)

    def test_a_long_list_is_cut_unless_all_of_it_is_asked_for(self):
        before = [row(f"snes/{i:02}.sfc", hash=md5(f"rom {i}"), console="3") for i in range(25)]
        after = [dict(r, console="7") for r in before]
        status, out, _ = self.compare(after, before=before)
        self.assertEqual(status, 0)
        self.assertIn(f"{ROOT}/snes/19.sfc  console: 3 -> 7\n    ... and 5 more (--all lists them)\n", out)
        self.assertNotIn(f"{ROOT}/snes/20.sfc", out)
        status, out, _ = self.compare(after, "--all", before=before)
        self.assertNotIn("more (--all", out)
        self.assertIn(f"{ROOT}/snes/24.sfc  console: 3 -> 7", out)

    def test_the_files_with_a_recorded_answer_are_checked_for_keeping_their_hash(self):
        answers = self.oracle({md5("a"): 11, md5("b"): 1100000012})
        status, out, _ = self.compare(self.BEFORE, "--oracle", answers)
        self.assertEqual(status, 0)
        self.assertIn("  recorded answers: 2 of the 2 files whose hash was in the oracle still have that hash\n", out)

        after = [dict(r) for r in self.BEFORE]
        after[0].update(hash=md5("moved"))
        del after[1]
        status, out, _ = self.compare(after, "--oracle", answers)
        self.assertIn("  recorded answers: 0 of the 2 files whose hash was in the oracle still have that hash\n"
                      f"    {ROOT}/snes/A.sfc\n    {ROOT}/snes/B.zip\n", out)

    def test_a_hash_written_in_capitals_is_the_hash_it_is(self):
        answers = self.oracle({md5("a"): 11, md5("b"): 22})
        before = [dict(r) for r in self.BEFORE]
        before[0].update(hash=md5("a").upper())
        after = [dict(r) for r in self.BEFORE]
        after[1].update(hash=md5("b").upper())
        status, out, _ = self.compare(after, "--oracle", answers, before=before)
        self.assertIn("recorded answers: 2 files have a hash that is in the oracle\n", out)
        self.assertIn("  recorded answers: 2 of the 2 files whose hash was in the oracle still have that hash\n", out)


class ReportTest(Tables):

    ROWS = [
        row("snes/A.sfc", state="MATCHED", hash=md5("a"), console="3"),
        row("snes/B.sfc", hash=md5("b"), console="3"),
        row("snes/hacks/C.sfc", hash=md5("c"), console="3", platform="hacks"),
        row("psx/Game/Game.cue", state="HASH_FAILED", platform="Game"),
        row("switch/Game.nsp", state="UNSUPPORTED", platform=""),
        row("Loose.zip", state="AMBIGUOUS_ARCHIVE"),
        row("psx/New.chd", state="UNSUPPORTED_FORMAT"),
        row("snes/Later.sfc", state="A_STATE_OF_A_LATER_BUILD"),
    ]

    def test_files_are_counted_by_the_folder_under_the_root_and_by_state(self):
        status, out, _ = self.run_report(self.table("t.tsv", self.ROWS))
        self.assertEqual(status, 0)
        self.assertIn("t.tsv: 8 files\n", out)
        table = [line.split() for line in out.split("\n\n")[1].splitlines()]
        # The states the ledger has today in their own order, and one it does
        # not have yet after them.
        self.assertEqual(table[0], ["collection", "files", "MATCHED", "NOT_FOUND", "UNSUPPORTED",
                                    "AMBIGUOUS_ARCHIVE", "UNSUPPORTED_FORMAT", "HASH_FAILED",
                                    "A_STATE_OF_A_LATER_BUILD"])
        self.assertEqual(table[1:], [
            ["psx", "2", "0", "0", "0", "0", "1", "1", "0"],
            # A file in the root itself is under the root's own name.
            ["roms", "1", "0", "0", "0", "1", "0", "0", "0"],
            ["snes", "4", "1", "2", "0", "0", "0", "0", "1"],
            ["switch", "1", "0", "0", "1", "0", "0", "0", "0"],
            ["total", "8", "1", "2", "1", "1", "1", "1", "1"],
        ])

    def test_every_state_of_the_ledger_has_its_place_in_the_order(self):
        # What ScanLedger.State has, which a test of the hasher holds this
        # list to. Read in three groups: what the source was asked and said,
        # what was passed over unread, what gave no hash.
        self.assertEqual(report.STATE_ORDER,
                         ["MATCHED", "NOT_FOUND", "KNOWN_UNSUPPORTED", "API_RETRY",
                          "UNSUPPORTED", "PLACEHOLDER",
                          "AMBIGUOUS_ARCHIVE", "NO_PLAYABLE_ENTRY", "UNSUPPORTED_FORMAT", "UNHASHABLE", "HASH_FAILED"])
        rows = [row(f"snes/{n}.sfc", state=state) for n, state in enumerate(reversed(report.STATE_ORDER))]
        _, out, _ = self.run_report(self.table("t.tsv", rows))
        self.assertEqual(out.split("\n\n")[1].splitlines()[0].split()[2:], report.STATE_ORDER)

    def test_with_no_root_named_a_file_is_counted_under_its_own_folder(self):
        status, out, _ = self.run_report(self.table("t.tsv", self.ROWS, comments=()))
        names = [line.split()[0] for line in out.split("\n\n")[1].splitlines()]
        self.assertEqual(names, ["collection", "Game", "hacks", "psx", "roms", "snes", "switch", "total"])

    def test_the_deepest_root_that_holds_a_file_is_the_one_it_is_counted_from(self):
        roots = (("root", ROOT), ("root", f"{ROOT}/snes"), ("root", f"{ROOT}/sn"))
        status, out, _ = self.run_report(self.table("t.tsv", self.ROWS, comments=roots))
        names = [line.split()[0] for line in out.split("\n\n")[1].splitlines()]
        self.assertEqual(names, ["collection", "hacks", "psx", "roms", "snes", "switch", "total"])

    def test_identified_is_what_the_scan_matched_and_what_the_oracle_knows(self):
        table = self.table("t.tsv", self.ROWS)
        status, out, _ = self.run_report(table, "--min-identified", "1")
        self.assertEqual(status, 0)
        self.assertIn("identified: 1\n", out)
        status, out, _ = self.run_report(table, "--min-identified", "2")
        self.assertEqual(status, 1)
        self.assertIn("FAILED: 1 identified, fewer than 2", out)

        # B is known under a real id. C is known as a dump that is not
        # playable as it is, which is an answer and not a game.
        answers = self.oracle({md5("b").upper(): 22, md5("c"): 1100000033, md5("elsewhere"): 44})
        status, out, _ = self.run_report(table, "--oracle", answers, "--min-identified", "2")
        self.assertEqual(status, 0, out)
        self.assertIn("identified: 2 (1 matched by the scan, 1 more by the oracle)\n", out)
        self.assertIn("recorded answers: 2 files have a hash that is in the oracle\n", out)
        self.assertEqual(self.run_report(table, "--oracle", answers, "--min-identified", "3")[0], 1)

    # The count --min-identified is held against, so a file is in it once.
    # Nearly every file an oracle knows is also one the scan matched with it.
    def test_a_file_the_scan_matched_and_the_oracle_knows_is_identified_once(self):
        rows = [
            row("snes/A.sfc", state="MATCHED", hash=md5("a"), console="3"),
            row("snes/B.sfc", state="MATCHED", hash=md5("b"), console="3"),
            row("snes/C.sfc", hash=md5("c"), console="3"),
            # The last id that is a game's, and the first that is not.
            row("snes/D.sfc", hash=md5("d"), console="3"),
            row("snes/E.sfc", hash=md5("e"), console="3"),
            # An answer that the hash is nobody's game.
            row("snes/F.sfc", hash=md5("f"), console="3"),
            row("snes/G.sfc", hash=md5("g").upper(), console="3"),
            row("snes/H.sfc", hash=md5("h"), console="3"),
        ]
        answers = self.oracle({md5("a"): 11, md5("c"): 33, md5("d"): 1000000000, md5("e"): 1000000001,
                               md5("f"): 0, md5("g"): 77})
        table = self.table("t.tsv", rows)
        status, out, _ = self.run_report(table, "--oracle", answers, "--min-identified", "5")
        self.assertEqual(status, 0, out)
        self.assertIn("identified: 5 (2 matched by the scan, 3 more by the oracle)\n", out)
        self.assertIn("recorded answers: 6 files have a hash that is in the oracle\n", out)
        status, out, _ = self.run_report(table, "--oracle", answers, "--min-identified", "6")
        self.assertEqual(status, 1, out)
        self.assertIn("FAILED: 5 identified, fewer than 6", out)

    def test_bytes_read_are_summed_by_collection(self):
        rows = [
            # Read twice, for the hash and for the digest.
            row("gba/A.gba", hash=md5("a"), size="1000", read="2000"),
            row("gba/B.gba", hash=md5("b"), size="500", read="1000"),
            # The hash needed the start of it.
            row("psx/Disc.bin", hash=md5("d"), size="700000000", read="2048"),
            # Handed to the hasher and answered for unread: a row of its own.
            row("psx/Large.bin", state="HASH_FAILED", size="900000000", read="0"),
            # Never handed: in no sum.
            row("switch/Game.nsp", state="UNSUPPORTED", size="12345"),
        ]
        comments = (("root", ROOT), ("hasher", "4 files handed, 5048 bytes read for them"),
                    ("read", "9000 bytes by the process during the scan"))
        status, out, _ = self.run_report(self.table("t.tsv", rows, comments=comments))
        self.assertEqual(status, 0)
        block = next(b for b in out.split("\n\n") if b.startswith("bytes read\n")).splitlines()
        self.assertEqual(block[1:3], ["# hasher: 4 files handed, 5048 bytes read for them",
                                      "# read: 9000 bytes by the process during the scan"])
        self.assertEqual([line.split() for line in block[3:]], [
            ["collection", "files", "read", "size"],
            ["gba", "2", "3000", "1500"],
            ["psx", "2", "2048", "1600000000"],
            ["TOTAL", "4", "5048", "1600001500"],
        ])

    def test_a_table_with_no_read_column_says_nothing_of_bytes(self):
        # The table of a build from before the column, and one of a rescan,
        # where the column is there and no file was handed to the hasher.
        old = [c for c in report.COLUMNS if c != "read"]
        for name, columns in (("old.tsv", old), ("rescan.tsv", report.COLUMNS)):
            status, out, _ = self.run_report(self.table(name, self.ROWS, columns=columns))
            self.assertEqual(status, 0)
            self.assertIn(f"{name}: 8 files\n", out)
            self.assertNotIn("bytes read", out)
            self.assertNotIn("TOTAL", out)

    def test_what_a_file_cost_to_read_is_not_compared(self):
        before = self.table("before.tsv", [row("gba/A.gba", hash=md5("a"), read="2000")])
        after = self.table("after.tsv", [row("gba/A.gba", hash=md5("a"), read="1000")])
        status, out, _ = self.run_report(after, "--baseline", before, "--max-changed", "0")
        self.assertEqual(status, 0)
        self.assertIn("): 0 differ\n", out)

    def test_files_the_audit_did_not_read_are_said_apart(self):
        rows = [row("psx/Big.iso", state="HASH_FAILED", detail="audit: larger than 1000"),
                row("psx/Bigger.iso", state="HASH_FAILED", detail="audit: larger than 1000"),
                row("psx/Bad.iso", state="HASH_FAILED", detail="audit: skipped"),
                row("psx/Broken.iso", state="HASH_FAILED", detail="the hasher could not read Broken.iso")]
        _, out, _ = self.run_report(self.table("t.tsv", rows))
        self.assertIn("2 of the files above were not read (audit: larger than 1000)", out)
        self.assertIn("1 of the files above were not read (audit: skipped)", out)
        # A file that was read and could not be hashed is not one of them.
        self.assertEqual(out.count("were not read"), 2, out)
        self.assertNotIn("the hasher could not read", out)

    def test_a_scan_that_stopped_early_is_said_first(self):
        comments = (("root", ROOT), ("stopped", "no internet connection (1 of 7 processed)"))
        _, out, _ = self.run_report(self.table("t.tsv", self.ROWS, comments=comments))
        self.assertIn("THE SCAN STOPPED EARLY (no internet connection (1 of 7 processed))", out.splitlines()[1])

    def test_a_cell_with_a_tab_or_a_backslash_in_it_is_read_back_whole(self):
        odd = row("snes/Odd\tname \\ here.sfc", hash=md5("o"), console="4")
        _, out, _ = self.run_report(self.table("t.tsv", [odd]))
        self.assertEqual(self.junk_line(out, "J4"), (1, [f"{ROOT}/snes/Odd\tname \\ here.sfc"]))
        comments, rows = report.read_table(self.table("u.tsv", [odd]))
        self.assertEqual(rows, [odd])
        self.assertEqual(comments, [("root", ROOT)])
        # A backslash before any other letter, in a table somebody wrote by
        # hand, is left as it is found.
        self.assertEqual(report.unescape("C:\\\\roms\\q\\tx\\"), "C:\\roms\\q\tx\\")

    # The root is a path too, and is written as a cell is.
    def test_a_root_with_a_tab_or_a_backslash_in_it_still_holds_its_files(self):
        root = "/library/odd\troot \\ here"
        rows = [dict(row("x"), path=f"{root}/snes/hacks/A.sfc"), dict(row("x"), path=f"{root}/nes/B.nes")]
        table = self.table("t.tsv", rows, comments=(("root", root),))
        self.assertEqual(report.read_table(table)[0], [("root", root)])
        _, out, _ = self.run_report(table)
        names = [line.split()[0] for line in out.split("\n\n")[1].splitlines()]
        self.assertEqual(names, ["collection", "nes", "snes", "total"])

    # The daemon runs on Windows as well, and writes the paths it has there.
    def test_a_table_of_windows_paths_is_counted_by_the_folder_under_the_root_too(self):
        root = "C:\\library\\roms"
        rows = [dict(row("x"), path=root + "\\snes\\hacks\\A.sfc", platform="hacks"),
                dict(row("x", hash=md5("alpha"), console="27"), path=root + "\\megadrive\\alpha.zip",
                     platform="megadrive", extension="zip"),
                dict(row("x", hash=md5("beta"), console="27"), path=root + "\\arcade\\sets\\beta.zip",
                     platform="sets", extension="zip")]
        _, out, _ = self.run_report(self.table("t.tsv", rows, comments=(("root", root),)))
        names = [line.split()[0] for line in out.split("\n\n")[1].splitlines()]
        self.assertEqual(names, ["collection", "arcade", "megadrive", "snes", "total"])
        self.assertEqual(self.junk_line(out, "J2"), (1, [root + "\\megadrive\\alpha.zip"]))

    def test_a_table_saved_with_the_line_ends_of_another_system_reads_the_same(self):
        plain = Path(self.table("t.tsv", self.ROWS))
        other = self.dir / "crlf.tsv"
        other.write_bytes(plain.read_bytes().replace(b"\n", b"\r\n"))
        self.assertEqual(report.read_table(str(other)), report.read_table(str(plain)))

    def test_collections_are_listed_in_the_order_of_a_dictionary(self):
        rows = [row("Zeta/a.sfc"), row("alpha/b.sfc"), row("Beta/c.sfc")]
        _, out, _ = self.run_report(self.table("t.tsv", rows))
        names = [line.split()[0] for line in out.split("\n\n")[1].splitlines()]
        self.assertEqual(names, ["collection", "alpha", "Beta", "Zeta", "total"])

    def test_a_table_of_a_later_build_with_columns_in_another_order_is_read_by_name(self):
        columns = ["recipe"] + list(reversed(report.COLUMNS))
        rows = [dict(row("megadrive/Game.sms", hash=md5("a"), console="4"), recipe="r1")]
        status, out, _ = self.run_report(self.table("t.tsv", rows, columns=columns))
        self.assertEqual(status, 0)
        self.assertEqual(self.junk_line(out, "J4"), (1, [f"{ROOT}/megadrive/Game.sms"]))

    def test_what_is_not_a_table_is_refused(self):
        missing = str(self.dir / "missing.tsv")
        self.assertEqual(self.run_report(missing)[0], 2)
        table = self.table("t.tsv", self.ROWS)
        self.assertEqual(self.run_report(table, "--baseline", missing)[0], 2)
        self.assertEqual(self.run_report(table, "--oracle", missing)[0], 2)

        empty = self.dir / "empty.tsv"
        empty.write_text("# root\t/library\n", encoding="utf-8")
        status, _, err = self.run_report(str(empty))
        self.assertEqual(status, 2)
        self.assertIn("no line that names the columns", err)

        for columns in ("name\tvalue", "path\tvalue", "name\tstate"):
            other = self.dir / "other.tsv"
            other.write_text(f"{columns}\na\tb\n", encoding="utf-8")
            status, _, err = self.run_report(str(other))
            self.assertEqual(status, 2, columns)
            self.assertIn("not an audit's table", err)

        # One row a file: two would make a comparison by path mean nothing.
        status, _, err = self.run_report(self.table("twice.tsv", [self.ROWS[0], self.ROWS[0]]))
        self.assertEqual(status, 2)
        self.assertIn("more than one row", err)

        bad = self.dir / "bad-answers.tsv"
        bad.write_text(f"{md5('a')}\tnot a number\n", encoding="utf-8")
        status, _, err = self.run_report(table, "--oracle", str(bad))
        self.assertEqual(status, 2)
        self.assertIn("line 1 is not a hash and a game id", err)

        self.assertEqual(self.run_report()[0], 2)
        self.assertEqual(self.run_report(table, "--no-such-flag")[0], 2)


if __name__ == "__main__":
    unittest.main()
