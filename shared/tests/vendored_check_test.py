#!/usr/bin/env python3
"""Tests vendored_check.py on a small made-up repository in a temporary
directory: an upstream tree of four files, one of them of a kind the tree's
own .gitignore hides, a patch on one of the others, and the manifest, the
PATCHES.md and the patch level that go with them. Each test spoils one thing
and asks that the check name it.

    ./tests/vendored_check_test.py

What the check refuses is not seen in an ordinary run, where the folder is
what the manifest says: a check that had stopped refusing would go on
printing that all is well. The last test runs it on the repository this file
is in. Every test needs git, as the check does, and is skipped without one.
"""

import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tests/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import vendored_check as check  # noqa: E402

SOURCE = "src/rhash/one.c"
HEADER = "include/one.h"
HIDDEN = "include/module.modulemap"
IGNORE = ".gitignore"
PATCH = "0001-one.patch"

UPSTREAM = {
    SOURCE: "int one(void)\n{\n  int a = 1;\n  int b = 2;\n  int c = 3;\n  int d = 4;\n"
            "  int e = 5;\n  int f = 6;\n  int g = 7;\n  int h = 8;\n  int i = 9;\n"
            "  return a + b + c + d + e + f + g + h + i;\n}\n",
    HEADER: "int one(void);\n",
    HIDDEN: "module one { header \"one.h\" }\n",
    # As upstream's own does: it hides the file above from a plain `git add`.
    IGNORE: "*.mod*\n*.out\n",
}

# Two hunks, far enough apart to be two: the first and the last line of the body.
PATCHED = UPSTREAM[SOURCE].replace("  int a = 1;\n", "  int a = 1; /* local patch 0001 */\n") \
                          .replace("  int i = 9;\n", "  int i = 9; /* local patch 0001 */\n")


@unittest.skipIf(shutil.which("git") is None, "no git to ask what is tracked")
class VendoredCheckTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.tree = self.root / check.TREE
        self.patches = self.root / check.PATCHES
        for name, text in UPSTREAM.items():
            self.write(self.tree / name, text)
        self.patches.mkdir(parents=True)
        self.write(self.patches / PATCH, "What the patch is for.\n\n" + self.diff(SOURCE, PATCHED))
        self.write(self.patches / "PATCHES.md", f"# Local patches\n\n## {PATCH}\n\n**What.** A comment.\n")
        self.write(self.root / check.PATCH_LEVEL, "#define PB_RCHEEVOS_PATCHLEVEL 1\n")
        self.write(self.tree / SOURCE, PATCHED)
        self.write_manifest()
        self.git("init", "-q")
        self.git("add", "-f", "--", check.CPP)

    def tearDown(self):
        self._tmp.cleanup()

    # ---- the made-up repository

    def write(self, path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))

    def git(self, *arguments):
        done = subprocess.run(["git", *arguments], cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(done.returncode, 0, done.stderr.decode("utf-8", "replace"))
        return done.stdout.decode("utf-8")

    def diff(self, name, changed):
        """git's own diff of an upstream file and `changed`, with the paths a
        patch file has: from the top of the vendored tree."""
        with tempfile.TemporaryDirectory() as scratch:
            scratch = Path(scratch)
            self.write(scratch / "a" / name, UPSTREAM[name])
            self.write(scratch / "b" / name, changed)
            done = subprocess.run(["git", "diff", "--no-index", "--no-color", f"a/{name}", f"b/{name}"],
                                  cwd=scratch, stdout=subprocess.PIPE)
            text = done.stdout.decode("utf-8")
        return text.replace(f"a/a/{name}", f"a/{name}").replace(f"b/b/{name}", f"b/{name}")

    def write_manifest(self, files=None, patches=(PATCH,), extra="", left_out=()):
        files = UPSTREAM if files is None else files
        lines = ["# what the folder is a copy of", "upstream v1.0.0 " + "ab" * 20]
        lines += [f"patch {name}" for name in patches]
        lines += [("left-out" if name in left_out else "file") + f" {check.blob_id(text.encode('utf-8'))} {name}"
                  for name, text in sorted(files.items())]
        self.write(self.root / check.MANIFEST, "\n".join(lines) + "\n" + extra)

    def run_check(self):
        """(status, what was printed)."""
        out = io.StringIO()
        with redirect_stdout(out), redirect_stderr(out):
            status = check.main(["vendored_check.py", str(self.root)])
        return status, out.getvalue()

    def refused(self, saying):
        status, said = self.run_check()
        self.assertEqual(status, 1, said)
        self.assertIn(saying, said)
        return said

    # ---- what passes

    def test_a_tree_that_is_upstream_and_its_patch_passes(self):
        status, said = self.run_check()
        self.assertEqual(status, 0, said)
        self.assertIn("rcheevos v1.0.0 (abababababab), 4 files and 0 left out on purpose, "
                      "with 1 local patches on 1 of them", said)

    def test_a_file_the_manifest_says_is_left_out_has_to_be_absent(self):
        self.write_manifest(left_out=(HEADER,))
        self.refused(f"{HEADER} is left out of the copy on purpose, the manifest says, and is in git here")
        # Out of git and still lying in the folder is not left out either.
        self.git("rm", "-q", "--cached", "--", f"{check.TREE}/{HEADER}")
        self.refused(f"{HEADER} is left out of the copy on purpose, the manifest says, and is in the folder")
        (self.tree / HEADER).unlink()
        status, said = self.run_check()
        self.assertEqual(status, 0, said)
        self.assertIn("3 files and 1 left out on purpose", said)

    # ---- the files

    def test_a_file_the_trees_own_gitignore_kept_out_of_git_is_named(self):
        self.git("rm", "-q", "--cached", "--", f"{check.TREE}/{HIDDEN}")
        # What a plain `git add` of the folder does with it: nothing.
        self.git("add", "--", check.TREE)
        said = self.refused(f"{HIDDEN} is in upstream's tree and not in git here")
        self.assertNotIn("nor in the folder", said)

    def test_a_file_of_upstream_that_is_gone_is_named(self):
        self.git("rm", "-q", "-f", "--", f"{check.TREE}/{HEADER}")
        self.refused(f"{HEADER} is in upstream's tree and not in git here, nor in the folder")

    def test_a_file_in_git_that_is_not_in_the_folder_is_named(self):
        # Deleted and not yet staged: git's list still has it, and there is
        # nothing in the folder to hold against upstream's.
        (self.tree / HEADER).unlink()
        said = self.refused(f"{HEADER} is in git here and not in the folder")
        self.assertNotIn("and nothing else", said)

    def test_a_link_where_upstream_has_a_file_is_named(self):
        # To a copy with upstream's very content, kept outside the folder:
        # read through the link, nothing in it differs.
        self.write(self.root / "elsewhere" / "one.h", UPSTREAM[HEADER])
        (self.tree / HEADER).unlink()
        try:
            (self.tree / HEADER).symlink_to(self.root / "elsewhere" / "one.h")
        except (OSError, NotImplementedError):
            self.skipTest("no links can be made here")
        self.refused(f"{HEADER} is a link in the folder, and upstream has a file of its own there")
        # In git as a link too, it is still not upstream's file.
        self.git("add", "-f", "--", check.TREE)
        self.refused(f"{HEADER} is a link in the folder, and upstream has a file of its own there")

    def test_a_link_in_the_folder_that_git_does_not_know_is_named(self):
        # One that leads nowhere is no file to anything that follows links.
        (self.tree / "test").mkdir()
        try:
            (self.tree / "test" / "b.out").symlink_to(self.root / "nowhere")
        except (OSError, NotImplementedError):
            self.skipTest("no links can be made here")
        self.refused("test/b.out is in the folder, and neither git nor upstream's tree has it")

    def test_a_file_in_git_that_upstream_does_not_have_is_named(self):
        self.write(self.tree / "src/ours.c", "int ours;\n")
        self.git("add", "--", check.TREE)
        self.refused("src/ours.c is in git here and not in upstream's tree")

    def test_a_file_in_the_folder_that_git_does_not_know_is_named(self):
        self.write(self.tree / "test/a.out", "built here\n")
        self.git("add", "--", check.TREE)      # hidden by the tree's .gitignore
        self.refused("test/a.out is in the folder, and neither git nor upstream's tree has it")

    # ---- the content

    def test_a_change_no_patch_names_is_named(self):
        self.write(self.tree / HEADER, "int one(void); /* changed by hand */\n")
        self.refused(f"{HEADER} is not as upstream has it, and no patch says how")

    def test_a_patch_that_was_not_applied_is_named(self):
        self.write(self.tree / SOURCE, UPSTREAM[SOURCE])
        self.refused(f"{SOURCE} is as upstream has it, and {PATCH} should have changed it")

    def test_a_patch_applied_in_part_does_not_come_off(self):
        self.write(self.tree / SOURCE, PATCHED.replace("  int i = 9; /* local patch 0001 */\n", "  int i = 9;\n"))
        self.refused(f"{PATCH} does not come off the tree")

    def test_a_change_beside_the_patch_in_a_patched_file_is_named(self):
        self.write(self.tree / SOURCE, PATCHED.replace("  int e = 5;\n", "  int e = 50;\n"))
        self.refused(f"{SOURCE} with {PATCH} taken off is not the file upstream has")

    def test_a_patch_of_a_file_upstream_does_not_have_is_named(self):
        text = (self.patches / PATCH).read_text().replace(SOURCE, "src/rhash/none.c")
        self.write(self.patches / PATCH, text)
        self.refused(f"{PATCH} changes src/rhash/none.c, which upstream's tree does not have")

    # ---- the three places that count the patches

    def test_a_patch_file_on_no_line_of_the_manifest_is_named(self):
        self.write_manifest(patches=())
        self.refused(f"{PATCH} is on no `patch` line of {check.MANIFEST}")

    def test_a_patch_line_with_no_file_is_named(self):
        self.write_manifest(patches=(PATCH, "0002-gone.patch"))
        self.refused(f"lists 0002-gone.patch, and {check.PATCHES} has no such file")

    def test_a_patch_with_no_section_of_its_own_is_named(self):
        self.write(self.patches / "PATCHES.md", "# Local patches\n\nNone is described.\n")
        self.refused(f"PATCHES.md has a section for no patch, and the patch files are {PATCH}")

    def test_a_patch_level_that_is_not_the_number_of_patches_is_named(self):
        self.write(self.root / check.PATCH_LEVEL, "#define PB_RCHEEVOS_PATCHLEVEL 6\n")
        self.refused("says 6 patches and there are 1 patch files")

    # ---- the manifest itself, and what the check needs

    def test_a_manifest_with_a_line_of_no_known_kind_is_not_read_in_part(self):
        self.write_manifest(extra="files 117\n")
        self.refused("cannot be read as a manifest: line 8 begins with 'files'")

    def test_a_manifest_that_names_no_upstream_is_refused(self):
        text = (self.root / check.MANIFEST).read_text().replace("upstream v1.0.0", "upstream")
        self.write(self.root / check.MANIFEST, text)
        self.refused("cannot be read as a manifest")

    def test_without_git_nothing_is_checked_and_that_is_said(self):
        path = os.environ.get("PATH", "")
        os.environ["PATH"] = str(self.root / "nowhere")
        try:
            status, said = self.run_check()
        finally:
            os.environ["PATH"] = path
        self.assertEqual(status, 2, said)
        self.assertIn("nothing was checked", said)

    def test_outside_a_checkout_nothing_is_checked_and_that_is_said(self):
        shutil.rmtree(self.root / ".git")
        ceiling = os.environ.get("GIT_CEILING_DIRECTORIES")
        os.environ["GIT_CEILING_DIRECTORIES"] = str(self.root.parent)
        try:
            status, said = self.run_check()
        finally:
            if ceiling is None:
                del os.environ["GIT_CEILING_DIRECTORIES"]
            else:
                os.environ["GIT_CEILING_DIRECTORIES"] = ceiling
        self.assertEqual(status, 2, said)
        self.assertIn("nothing was checked", said)


class RepositoryTest(unittest.TestCase):
    """The one test here that looks at the real repository."""

    def test_the_vendored_rcheevos_of_this_repository_passes(self):
        if shutil.which("git") is None or not (check.REPOSITORY / ".git").exists():
            self.skipTest("not a git checkout, or no git to ask")
        wrong, read = check.check(check.REPOSITORY)
        self.assertEqual(wrong, [])
        # The check goes by what the manifest lists, so a manifest of three
        # files would pass as well as one of the whole tree.
        self.assertGreater(read["files"], 100)
        self.assertGreater(read["patched"], 0)


if __name__ == "__main__":
    unittest.main()
