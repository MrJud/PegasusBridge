#!/usr/bin/env python3
"""Tests native_manifest_check.py on a small made-up repository in a temporary
directory: a list of two sources, a header, a JNI file, a library of a few
bytes and the manifest build.sh would write for them. Each test spoils one
thing and asks that the check name it.

    ./tests/native_manifest_check_test.py

The check is what stands between a library committed as a binary and C that
has moved on without it, and what it refuses is not seen in an ordinary run,
where the manifest holds: a check that had stopped refusing would go on
printing that all is well. It compiles nothing, and neither does this.

One test more asks git, where there is a git and a checkout, whether it would
hand out the files the committed manifest names with their line endings
changed. It would not show in the sums here, only in somebody else's checkout.
"""

import hashlib
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tests/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import native_manifest_check as check  # noqa: E402

CPP = "hasher/src/main/cpp"
LIST = f"{CPP}/rahasher.sources"
ONE = f"{CPP}/rcheevos/src/rhash/one.c"
TWO = f"{CPP}/rcheevos/src/rhash/two.c"
HEADER = f"{CPP}/rcheevos/include/one.h"
MAP = f"{CPP}/jni.map"
SCRIPT = "shared/native/build.sh"
JNI = f"{CPP}/rahasher_jni.c"
LIBRARY = "librahasher.so"

NO_JNI = f"the manifest lists no C file but those {LIST} names"


class Tree:
    """A repository with a library in shared/native/out and the manifest of a
    build that has just finished: every file named, every sum right."""

    FILES = {
        # A comment, a blank line, a comment after a name and space around
        # one: all of which build.sh reads past.
        LIST: "# the sources, one to a line\n\nrcheevos/src/rhash/one.c   # the first\n"
              "  rcheevos/src/rhash/two.c\n",
        ONE: '#include "one.h"\nint one(void) { return ONE; }\n',
        TWO: "int two(void) { return 2; }\n",
        HEADER: "#define ONE 1\n",
        MAP: "{ global: Java_*; local: *; };\n",
        SCRIPT: "#!/usr/bin/env bash\n# stands for the script; it is never run\n",
        JNI: "int Java_stub(void) { return 0; }\n",
    }

    def __init__(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        for name, text in self.FILES.items():
            self.write(name, text)
        self.out = self.root / "shared" / "native" / "out"
        self.out.mkdir(parents=True)
        (self.out / LIBRARY).write_bytes(b"\x7fELF, or near enough")
        self.manifest = self.out / "librahasher.manifest"
        self.write_manifest(sorted(self.FILES))

    def close(self):
        self._tmp.cleanup()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def sum(self, path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def lines(self, inputs):
        return ["# What librahasher.so beside this file was compiled from.",
                f"library {self.sum(self.out / LIBRARY)} {LIBRARY}",
                "compiler gcc (made up) 1.0",
                "flags -std=gnu11 -O2 -shared",
                *(f"input {self.sum(self.root / name)} {name}" for name in inputs)]

    def write_manifest(self, inputs=None, lines=None):
        text = "\n".join(lines if lines is not None else self.lines(inputs)) + "\n"
        self.manifest.write_text(text, encoding="utf-8")

    def without(self, *names):
        """The manifest again, as it was but for the lines of these files."""
        self.write_manifest([name for name in sorted(self.FILES) if name not in names])

    def run(self, *arguments):
        out, err = io.StringIO(), io.StringIO()
        argv = ["native_manifest_check.py", *(arguments or (str(self.manifest),))]
        with redirect_stdout(out), redirect_stderr(err):
            status = check.main(argv, repository=self.root)
        return status, out.getvalue() + err.getvalue()


class ManifestCheckTest(unittest.TestCase):

    def setUp(self):
        self.tree = Tree()
        self.addCleanup(self.tree.close)

    def refused(self, *said, count=1):
        """The check fails, says each of `said`, and counts `count` things."""
        status, output = self.tree.run()
        self.assertEqual(status, 1, output)
        for text in said:
            self.assertIn(text, output)
        self.assertIn(f"{count} thing(s) wrong", output)
        self.assertIn("To put it right: run native/build.sh", output)
        return output

    # ---- a manifest that holds

    def test_a_tree_as_the_build_left_it_passes(self):
        status, output = self.tree.run()
        self.assertEqual(status, 0, output)
        self.assertIn("librahasher.so is the library its manifest describes", output)
        self.assertIn("the 7 files it was compiled from are unchanged", output)
        self.assertIn("compiler: gcc (made up) 1.0", output)

    def test_a_manifest_without_the_linkers_list_passes(self):
        # jni.map is Linux's: a build on another system has no line for it.
        self.tree.without(MAP)
        status, output = self.tree.run()
        self.assertEqual(status, 0, output)
        self.assertIn("the 6 files", output)

    # ---- files that are not what they were

    def test_every_kind_of_input_that_changes_is_named(self):
        for name in (ONE, TWO, HEADER, MAP, SCRIPT, JNI):
            with self.subTest(name):
                tree = self.tree = Tree()
                self.addCleanup(tree.close)
                tree.write(name, tree.FILES[name] + "/* and one line more */\n")
                self.refused(f"{name} has changed since the library was compiled")

    def test_a_changed_list_of_sources_is_named(self):
        self.tree.write(LIST, self.tree.FILES[LIST] + "# a comment more\n")
        self.refused(f"{LIST} has changed since the library was compiled")

    def test_a_source_added_to_the_list_since_the_build_is_two_things_wrong(self):
        self.tree.write(f"{CPP}/rcheevos/src/rhash/three.c", "int three;\n")
        self.tree.write(LIST, self.tree.FILES[LIST] + "rcheevos/src/rhash/three.c\n")
        self.refused(f"{LIST} has changed since the library was compiled",
                     f"{LIST} names rcheevos/src/rhash/three.c, and the manifest has no line for it",
                     count=2)

    def test_an_input_that_is_gone_is_named(self):
        (self.tree.root / HEADER).unlink()
        self.refused(f"{HEADER} was compiled into the library and is no longer there")

    def test_another_library_in_its_place_is_named(self):
        (self.tree.out / LIBRARY).write_bytes(b"\x7fELF of another build")
        self.refused("librahasher.so is not the library this manifest was written for")

    def test_a_library_that_is_gone_is_named(self):
        (self.tree.out / LIBRARY).unlink()
        self.refused("librahasher.so is not beside the manifest")

    # ---- lines the manifest ought to have

    def test_a_source_on_the_list_with_no_line_is_refused(self):
        # Nothing has changed in the tree: it is the manifest that says too
        # little. Were this let through, two.c could change from here on and
        # no sum would be held against it.
        self.tree.without(TWO)
        self.refused(f"{LIST} names rcheevos/src/rhash/two.c, and the manifest has no line for it")

    def test_a_source_with_no_line_is_refused_once_it_changes_too(self):
        self.tree.without(ONE)
        self.tree.write(ONE, "int one(void) { return 100; }\n")
        self.refused(f"{LIST} names rcheevos/src/rhash/one.c, and the manifest has no line for it")

    def test_a_manifest_that_names_no_c_at_all_is_refused_for_each_source(self):
        # What a build would write if its compiler could not say what it had
        # read and the build went on: the script, the list and the linker's
        # list, and all three sums right.
        self.tree.without(ONE, TWO, HEADER, JNI)
        self.refused("names rcheevos/src/rhash/one.c, and the manifest has no line for it",
                     "names rcheevos/src/rhash/two.c, and the manifest has no line for it",
                     NO_JNI, count=3)

    def test_a_manifest_without_the_jni_file_is_refused(self):
        self.tree.without(JNI)
        self.refused(NO_JNI)

    def test_a_jni_file_of_another_name_in_another_folder_will_do(self):
        # The file may be renamed and moved, beside the sources of the list for
        # one: it is asked for as a C file the list does not name.
        other = f"{CPP}/pb_jni.c"
        self.tree.write(other, "int Java_other(void) { return 0; }\n")
        self.tree.write_manifest([n for n in sorted(self.tree.FILES) if n != JNI] + [other])
        status, output = self.tree.run()
        self.assertEqual(status, 0, output)

    def test_a_manifest_without_the_script_or_the_list_is_refused(self):
        for name in (SCRIPT, LIST):
            with self.subTest(name):
                self.tree.without(name)
                self.refused(f"the manifest does not list {name}, which every build reads")

    def test_a_list_that_is_gone_is_said_once_and_does_not_end_the_check(self):
        (self.tree.root / LIST).unlink()
        self.refused(f"{LIST} was compiled into the library and is no longer there")

    # ---- manifests that are not manifests

    def test_no_manifest_is_refused(self):
        self.tree.manifest.unlink()
        self.refused("there is no manifest at")

    def test_a_line_no_build_writes_is_refused(self):
        self.tree.write_manifest(lines=self.tree.lines(sorted(self.tree.FILES)) + ["built today"])
        self.refused("line 12 begins with 'built', which is no kind of line build.sh writes")

    def test_a_sum_that_is_not_a_sum_is_refused(self):
        self.tree.write_manifest(lines=self.tree.lines(sorted(self.tree.FILES))
                                 + [f"input abc123 {ONE}"])
        self.refused("line 12 is not `input <sha256> <path>`")

    def test_two_libraries_or_none_are_refused(self):
        lines = self.tree.lines(sorted(self.tree.FILES))
        self.tree.write_manifest(lines=lines + [lines[1]])
        self.refused("the manifest names 2 libraries where it must name one")
        self.tree.write_manifest(lines=[lines[0]] + lines[2:])
        self.refused("the manifest names 0 libraries where it must name one")

    # ---- how it is called

    def test_an_option_or_a_second_argument_is_a_usage_error(self):
        for arguments in (("--help",), ("a.manifest", "b.manifest")):
            with self.subTest(arguments):
                status, output = self.tree.run(*arguments)
                self.assertEqual(status, 2)
                self.assertIn("usage: native_manifest_check.py [manifest]", output)

    def test_the_list_is_read_as_the_build_reads_it(self):
        self.assertEqual(check.listed_sources(self.tree.root), [ONE, TWO])


class CheckoutTest(unittest.TestCase):
    """The one test here that looks at the real repository: at what git is
    told to do with the files the committed manifest names."""

    def test_git_hands_out_every_input_of_the_committed_manifest_as_it_is(self):
        # The sums are of the bytes in git. A checkout that turns LF into CRLF
        # (core.autocrlf, which Git for Windows sets) gives the check other
        # bytes, and it then says of an untouched tree that the library needs
        # building again. .gitattributes is what stops that, and git answers
        # from it whatever this checkout's own settings are.
        git = shutil.which("git")
        if git is None or not (check.REPOSITORY / ".git").exists():
            self.skipTest("not a git checkout, or no git to ask")
        _, inputs, _ = check.read_manifest(check.DEFAULT)
        names = [name for _, name in inputs]
        self.assertTrue(names, "the committed manifest names no input")
        done = subprocess.run([git, "check-attr", "-z", "text", "--", *names],
                              cwd=str(check.REPOSITORY), stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(done.returncode, 0, done.stderr.decode("utf-8", "replace"))
        # -z: path, attribute and value, each ended by a NUL.
        fields = done.stdout.decode("utf-8").split("\0")
        answers = dict(zip(fields[0::3], fields[2::3]))
        rewritten = [name for name in names if answers.get(name) != "unset"]
        self.assertEqual(rewritten, [],
                         "a checkout may rewrite the line endings of these, and the sums in "
                         "the manifest would no longer hold: give them `-text` in .gitattributes")


if __name__ == "__main__":
    unittest.main()
