#!/usr/bin/env python3
"""Tests rahasher_compare.py with two programs written here, one standing for
our hasher and one for RAHasher, each answering for a file as the test tells
it: a hash, nothing, an end by a signal, or no end at all.

    ./tools/rahasher_compare_test.py

Neither RAHasher nor our own program is run, and nothing is downloaded. One
test writes the real images, to see which of them the comparison takes; the
others compare files that are only names.
"""

import io
import json
import resource
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tools/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import rahasher_compare as compare  # noqa: E402

A, B, C = "a" * 32, "b" * 32, "c" * 32

# Answers from the file of its own name with .json after it: what to print,
# what to end with, whether to end by a signal or not to end. It is run as
# both programs are, with a console and a name, in the folder the file is
# in, and ends with 99 where that is not so: a cue sheet names its tracks
# from where it lies.
STUB = """#!/usr/bin/env python3
import json, os, signal, sys, time
answers = json.load(open(sys.argv[0] + ".json"))
if len(sys.argv) != 3 or not sys.argv[1].isdigit() or not os.path.isfile(sys.argv[2]):
    sys.exit(99)
how = answers.get(sys.argv[2], answers.get("*", {}))
if how.get("console") not in (None, sys.argv[1]):
    sys.exit(98)
sys.stdout.write(how.get("out", ""))
sys.stderr.write(how.get("err", ""))
sys.stdout.flush()
if how.get("signal"):
    os.kill(os.getpid(), signal.SIGSEGV)
time.sleep(how.get("sleep", 0))
sys.exit(how.get("exit", 0))
"""


def ours_says(digest, console=9):
    """What our program prints for a hash, and how it refuses."""
    if digest is None:
        return {"err": "CHD: not hashed\n", "exit": 1}
    return {"out": f"{digest}|{console}\n"}


def theirs_says(digest):
    """RAHasher's hash on a line, and the empty line it prints for none."""
    return {"out": f"{digest}\n"} if digest else {"out": "\n", "err": "Not a CD\n", "exit": 1}


class Programs(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.discs = self.dir / "discs"
        self.discs.mkdir()
        self.ours, self.theirs = self.dir / "ours", self.dir / "theirs"
        for program in (self.ours, self.theirs):
            program.write_text(STUB, encoding="utf-8")
            program.chmod(0o755)
        self.answers({}, {})

    def tearDown(self):
        self._tmp.cleanup()

    def answers(self, ours, theirs):
        for program, table in ((self.ours, ours), (self.theirs, theirs)):
            Path(str(program) + ".json").write_text(json.dumps(table), encoding="utf-8")
            for name in table:
                if name != "*":
                    (self.discs / name).write_bytes(b"")

    def compared(self, lines, seconds=20):
        return compare.compare(lines, self.ours, self.theirs, self.discs, seconds)


class ClassTest(Programs):

    def one(self, our_image, their_image, our_disc=A, their_disc=A):
        """The row of an image with a disc, each program answering with these."""
        self.answers({"i.chd": ours_says(our_image), "d.cue": ours_says(our_disc)},
                     {"i.chd": theirs_says(their_image), "d.cue": theirs_says(their_disc)})
        (row,) = self.compared([compare.Line("i.chd", 9, disc="d.cue")])
        self.assertEqual(row[:2] + row[4:5], ["i.chd", "9", "d.cue"])
        self.assertEqual(row[2:4] + row[5:7], [h or "-" for h in (our_image, their_image, our_disc, their_disc)])
        return row[-1]

    def test_each_class_is_what_the_two_programs_make_of_an_image_and_its_disc(self):
        for our_image, their_image, our_disc, their_disc, expected in (
                (A, A, A, A, "AGREE"),
                (None, None, A, A, "BOTH-REFUSE"),
                (None, A, A, A, "WE-REFUSE-THEIRS-IS-THE-DISC'S"),
                (None, B, A, A, "WE-REFUSE-THEIRS-IS-ANOTHER"),
                (A, None, A, A, "ONLY-OURS"),
                (A, B, A, A, "DIFFER"),
                # The two already part on the plain disc: the rcheevos in
                # each is another, whatever the image is read as.
                (A, B, A, B, "VERSION")):
            with self.subTest(ours=our_image, theirs=their_image, discs=(our_disc, their_disc)):
                self.assertEqual(self.one(our_image, their_image, our_disc, their_disc), expected)

    def test_the_cases_between_the_classes_fall_where_they_should(self):
        # From the hashes alone, with no program run: the rule is one function.
        for our_image, their_image, our_disc, their_disc, expected in (
                (A, B, A, C, "VERSION"),
                (A, None, A, None, "VERSION"),
                (A, None, None, A, "VERSION"),
                # Agreeing on the image is agreeing, whatever the disc.
                (A, A, A, B, "AGREE"),
                (None, None, A, B, "BOTH-REFUSE"),
                # What RAHasher gives is held against its own reading of
                # the disc, and against ours where it has none.
                (None, B, A, B, "WE-REFUSE-THEIRS-IS-THE-DISC'S"),
                (None, A, A, None, "WE-REFUSE-THEIRS-IS-THE-DISC'S"),
                (None, A, A, compare.CRASHED, "WE-REFUSE-THEIRS-IS-THE-DISC'S"),
                (None, B, A, None, "WE-REFUSE-THEIRS-IS-ANOTHER"),
                (None, A, None, None, "WE-REFUSE-THEIRS-IS-ANOTHER"),
                # A disc neither hashes says nothing of the versions, and
                # neither does one RAHasher did not come back from.
                (A, B, None, None, "DIFFER"),
                (A, B, A, compare.CRASHED, "DIFFER"),
                (A, None, A, compare.CRASHED, "ONLY-OURS"),
                (A, compare.CRASHED, A, A, "THEIRS-CRASHED"),
                (None, compare.CRASHED, A, B, "THEIRS-CRASHED")):
            with self.subTest(ours=our_image, theirs=their_image, discs=(our_disc, their_disc)):
                self.assertEqual(compare.classify(our_image, their_image, our_disc, their_disc), expected)

    def test_an_answer_stands_for_the_disc_where_there_is_none_to_hash(self):
        self.answers({"same.chd": ours_says(A), "other.chd": ours_says(A), "refused.chd": ours_says(None),
                      "strict.chd": ours_says(None), "unknown.chd": ours_says(None)},
                     {"same.chd": theirs_says(A), "other.chd": theirs_says(B), "refused.chd": theirs_says(None),
                      "strict.chd": theirs_says(A), "unknown.chd": theirs_says(B)})
        rows = self.compared([compare.Line("same.chd", 9, expected=A), compare.Line("other.chd", 9, expected=A),
                              compare.Line("refused.chd", 9, expected=A), compare.Line("strict.chd", 9, expected=A),
                              compare.Line("unknown.chd", 9)])
        self.assertEqual([row[-1] for row in rows],
                         ["AGREE", "DIFFER", "BOTH-REFUSE", "WE-REFUSE-THEIRS-IS-THE-DISC'S",
                          "WE-REFUSE-THEIRS-IS-ANOTHER"])
        # The answer is in the column of the disc, and in neither program's.
        self.assertEqual(rows[1], ["other.chd", "9", A, B, A, "-", "-", "DIFFER"])
        self.assertEqual(rows[4][4:7], ["-", "-", "-"])

    def test_rahashers_hash_is_a_first_line_of_32_hex_digits_and_nothing_else(self):
        for says, expected in (
                ({"out": f"{A}\r\n"}, A),
                # Whatever it ends with: only the line is read.
                ({"out": f"{A}\n", "exit": 1}, A),
                ({"out": "\n", "exit": 1}, None),
                ({"out": ""}, None),
                # As -v prints: a message first, and the hash after it.
                ({"out": f"Found a CD\nGenerated hash {A}\n{A}\n"}, None),
                ({"out": f"{A[:31]}\n"}, None),
                ({"out": f"{A}0\n"}, None),
                ({"out": f"{A.upper()}\n"}, None),
                ({"out": "", "err": f"{A}\n"}, None)):
            with self.subTest(says=says):
                self.answers({"i.chd": ours_says(A)}, {"i.chd": says})
                (row,) = self.compared([compare.Line("i.chd", 9, expected=A)])
                self.assertEqual(row[3], expected or "-")
                self.assertEqual(row[-1], "AGREE" if expected else "ONLY-OURS")

    def test_rahasher_ended_by_a_signal_or_by_its_time_is_a_class_and_no_failure(self):
        self.answers({"i.chd": ours_says(A)}, {"i.chd": {"out": f"{A}\n", "signal": True}})
        (row,) = self.compared([compare.Line("i.chd", 9, expected=A)])
        self.assertEqual(row[3:], ["crashed", A, "-", "-", "THEIRS-CRASHED"])

        self.answers({"i.chd": ours_says(None)}, {"i.chd": {"sleep": 30}})
        (row,) = self.compared([compare.Line("i.chd", 9, expected=A)], seconds=0.5)
        self.assertEqual(row[2:4] + row[-1:], ["-", "crashed", "THEIRS-CRASHED"])

        # On the disc alone: the image is still judged, with our disc's hash.
        self.answers({"i.chd": ours_says(None), "d.cue": ours_says(A)},
                     {"i.chd": theirs_says(A), "d.cue": {"signal": True}})
        (row,) = self.compared([compare.Line("i.chd", 9, disc="d.cue")])
        self.assertEqual(row[5:], [A, "crashed", "WE-REFUSE-THEIRS-IS-THE-DISC'S"])

    def test_both_programs_are_told_the_console_and_run_where_the_file_is(self):
        self.answers({"i.chd": dict(ours_says(A, 40), console="40")},
                     {"i.chd": dict(theirs_says(A), console="40")})
        self.assertEqual(self.compared([compare.Line("i.chd", 40, expected=A)])[0][-1], "AGREE")
        # Told another, the program here ends with 98, which is no answer
        # of ours, and no hash of theirs.
        with self.assertRaises(compare.CannotRun):
            self.compared([compare.Line("i.chd", 9, expected=A)])

    def test_our_program_ending_any_other_way_stops_the_comparison(self):
        for says, words in (({"signal": True}, "signal SIGSEGV"),
                            ({"out": "usage\n", "exit": 2}, "exit 2"),
                            # A hash and a status that is not 0, a refusal with something printed.
                            ({"out": f"{A}|9\n", "exit": 1}, "exit 1"),
                            ({"out": f"{A}\n"}, "exit 0"),
                            ({"sleep": 30}, "still running")):
            with self.subTest(says=says):
                self.answers({"i.chd": says}, {"i.chd": theirs_says(A)})
                with self.assertRaises(compare.CannotRun) as raised:
                    self.compared([compare.Line("i.chd", 9, expected=A)], seconds=0.5 if "sleep" in says else 20)
                self.assertIn(words, str(raised.exception))
                self.assertIn("i.chd", str(raised.exception))


class MainTest(Programs):

    def setUp(self):
        super().setUp()
        self.real = compare.fixture_lines

        def few(directory):
            for name in ("one.chd", "two.chd"):
                (directory / name).write_bytes(b"")
            return [compare.Line("one.chd", 9, expected=A), compare.Line("two.chd", 9, expected=A)]
        compare.fixture_lines = few

    def tearDown(self):
        compare.fixture_lines = self.real
        super().tearDown()

    def run_main(self, *more):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            status = compare.main(["rahasher_compare.py", "--rahasher", str(self.theirs),
                                   "--ours", str(self.ours), *more])
        return status, out.getvalue(), err.getvalue()

    def test_the_status_is_1_for_a_hash_that_differs_and_for_nothing_else(self):
        table = self.dir / "table.tsv"
        self.answers({"*": ours_says(A)}, {"*": theirs_says(A)})
        status, out, err = self.run_main("--out", str(table))
        self.assertEqual(status, 0, err)
        self.assertEqual(out.splitlines(), ["\t".join(compare.COLUMNS),
                                            f"one.chd\t9\t{A}\t{A}\t{A}\t-\t-\tAGREE",
                                            f"two.chd\t9\t{A}\t{A}\t{A}\t-\t-\tAGREE"])
        self.assertEqual(table.read_text(encoding="utf-8"), out)
        self.assertIn("2 images: 2 AGREE", err)

        # Every class there is but the one, and the status is still 0.
        for ours, theirs in ((None, None), (None, A), (None, B), (A, None)):
            self.answers({"*": ours_says(ours)}, {"*": theirs_says(theirs)})
            self.assertEqual(self.run_main()[0], 0, (ours, theirs))
        self.answers({"*": ours_says(A)}, {"*": {"signal": True}})
        status, out, _ = self.run_main()
        self.assertEqual((status, out.count("THEIRS-CRASHED")), (0, 2))

        self.answers({"*": ours_says(A), "two.chd": ours_says(B)}, {"*": theirs_says(A)})
        status, out, err = self.run_main()
        self.assertEqual(status, 1)
        self.assertIn("1 AGREE, 1 DIFFER", err)

    def test_a_file_of_the_callers_own_gets_a_line_with_no_disc(self):
        mine = self.dir / "elsewhere" / "Invented Disc.chd"
        mine.parent.mkdir()
        mine.write_bytes(b"")
        self.answers({"*": ours_says(A), mine.name: ours_says(B, 21)}, {"*": theirs_says(A)})
        status, out, _ = self.run_main("--also", f"21:{mine}")
        self.assertEqual(status, 1)
        self.assertEqual(out.splitlines()[-1], f"Invented Disc.chd\t21\t{B}\t{A}\t-\t-\t-\tDIFFER")

        self.assertEqual(self.run_main("--also", str(mine))[0], 2)
        status, _, err = self.run_main("--also", f"21:{mine}.not-there")
        self.assertEqual(status, 2)
        self.assertIn("no file at", err)

    def test_a_program_that_cannot_be_run_is_status_2(self):
        self.answers({"*": ours_says(A)}, {"*": theirs_says(A)})
        self.theirs.chmod(0o644)
        status, _, err = self.run_main()
        self.assertEqual(status, 2)
        self.assertIn("is not a program that can be run", err)
        self.theirs.chmod(0o755)

        self.ours.unlink()
        self.assertEqual(self.run_main()[0], 2)

        # Ours there, and ending as it never should.
        self.ours.write_text(STUB, encoding="utf-8")
        self.ours.chmod(0o755)
        self.answers({"*": {"signal": True}}, {"*": theirs_says(A)})
        status, out, err = self.run_main()
        self.assertEqual((status, out), (2, ""))
        self.assertIn("signal SIGSEGV", err)

        with redirect_stderr(io.StringIO()):
            self.assertEqual(compare.main(["rahasher_compare.py"]), 2)


class FixturesTest(unittest.TestCase):

    def test_the_images_compared_are_chdmans_the_well_formed_and_the_refused(self):
        import native_repro_test as repro
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            lines = compare.fixture_lines(tmp)
            by_name = {line.image: line for line in lines}
            self.assertEqual(len(by_name), len(lines))
            for line in lines:
                self.assertTrue((tmp / line.image).is_file(), line.image)
                self.assertTrue(line.disc is None or (tmp / line.disc).is_file(), line.image)
                self.assertGreater(line.console, 0, line.image)

        # chdman's, every one, each with the disc it was made from.
        self.assertEqual([(line.image, line.disc, line.console) for line in lines[:len(repro.CHDMAN_IMAGES)]],
                         [(name, source, console) for name, source, _, console, _ in repro.CHDMAN_IMAGES])
        self.assertEqual(len(repro.CHDMAN_IMAGES), 14)
        # A hashed image is held to the answer its row expects.
        self.assertRegex(by_name["segacd.chd"].expected, "^[0-9a-f]{32}$")
        self.assertEqual(by_name["segacd-cdzs.chd"].expected, by_name["segacd.chd"].expected)
        # The ones our reader refuses with nothing damaged in them: each is
        # held to the image of the same disc that is hashed, or to its disc.
        for name, disc in list(compare.NOT_PLACED.items()) + [("dreamcast-last-pregap.chd", "dreamcast-last.chd")]:
            self.assertEqual(by_name[name].expected, by_name[disc].expected, name)
            self.assertIsNotNone(by_name[name].expected, name)
        self.assertEqual(by_name["pce-largest-first.chd"].disc, "d-largest.cue")
        self.assertEqual(by_name["pce-largest-first.chd"].console, 49)
        self.assertEqual(by_name["dreamcast-last-first.chd"].disc, "d-last.gdi")
        # Every image the fixtures refuse as not placed is here: one added
        # there with no disc named for it in NOT_PLACED has no answer.
        self.assertIn("jaguar.chd", by_name)
        self.assertEqual([name for name, line in by_name.items()
                          if line.disc is None and line.expected is None], ["jaguar.chd"])
        # The ones made to be damaged are not, nor a file that is not there.
        for name in ("v4.chd", "crc.chd", "mapcut.chd", "selfchain.chd", "tracks100.chd", "probe-short.chd",
                     "no-such-track.chd", "missing.chd", "segacd-chd.m3u"):
            self.assertNotIn(name, by_name)


if __name__ == "__main__":
    # The programs here end by a signal on purpose, and leave no core file.
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    unittest.main()
