#!/usr/bin/env python3
"""Tests the check of the Android library's page size on files made to fail it.

    ./tests/page_size_check_test.py

hasher/src/main/cpp/check_page_size.cmake runs inside the Android build, after
the library is linked, and fails the build when a segment of the library is
aligned to less than 16 KB; the build has it remove the library it refused.
In an ordinary build there is nothing for it to refuse, and no job of CI
builds Android at all, so this is where the check itself is held: on small
libraries linked here for pages of 4 KB and of 16 KB, and on files that are
no library, or not the kind it reads.

It needs gcc, for the two libraries, and cmake, which runs the script. Any
gcc that writes ELF will do: what is read is the alignment the linker was
asked for, which is the same field whatever the processor. Where either is
missing the tests are skipped and say so, except in CI, where they fail.
"""

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
REPOSITORY = TESTS.parent.parent
SCRIPT = REPOSITORY / "hasher" / "src" / "main" / "cpp" / "check_page_size.cmake"

PAGE = 16384


def elf64(headers):
    """A 64-bit little-endian ELF file of nothing but its program headers,
    each given as (type, alignment)."""
    ident = b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
    # e_phoff is 64, straight after this header; e_phentsize is 56.
    header = ident + struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, 64, 0, 0, 64, 56, len(headers), 0, 0, 0)
    assert len(header) == 64
    return header + b"".join(struct.pack("<IIQQQQQQ", kind, 4, 0, 0, 0, 0, 0, align) for kind, align in headers)


class PageSizeCheckTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        for tool in ("gcc", "cmake"):
            if shutil.which(tool) is None:
                # Not in CI, where this is the one place the check is held:
                # skipped there, the job would pass with nothing looked at.
                if os.environ.get("GITHUB_ACTIONS"):
                    raise AssertionError(f"{tool} is not on PATH, and nothing else in CI holds this check")
                raise unittest.SkipTest(f"{tool} is not on PATH")
        cls.scratch = tempfile.TemporaryDirectory(prefix="page-size-")
        cls.dir = Path(cls.scratch.name)
        (cls.dir / "one.c").write_text("int one(void) { return 1; }\n")

    @classmethod
    def tearDownClass(cls):
        cls.scratch.cleanup()

    def linked(self, page):
        """A small shared library whose linker was told pages are `page` bytes."""
        library = self.dir / f"libone-{page}.so"
        subprocess.run(["gcc", "-shared", "-fPIC", "-o", str(library), str(self.dir / "one.c"),
                        f"-Wl,-z,max-page-size={page}"], check=True)
        return library

    def written(self, name, content):
        path = self.dir / name
        path.write_bytes(content)
        return path

    def check(self, library, page=PAGE, *more):
        return subprocess.run(["cmake", f"-DLIBRARY={library}", f"-DPAGE={page}", *more, "-P", str(SCRIPT)],
                              capture_output=True, text=True)

    @staticmethod
    def said(done):
        """What cmake printed, on one line: it breaks a message where the
        line is full, which goes by how long the file's path is."""
        return " ".join((done.stdout + done.stderr).split())

    def assertRefused(self, done, *words):
        said = self.said(done)
        self.assertNotEqual(0, done.returncode, said)
        for word in words:
            self.assertIn(word, said)

    def test_a_library_linked_for_4_kb_pages_is_refused_with_its_segments_named(self):
        done = self.check(self.linked(4096))
        self.assertRefused(done, "LOAD segment 1 ", "is aligned to 0x1000", "0x4000", "max-page-size=16384")

    def test_a_library_that_is_refused_is_removed_when_the_build_asks(self):
        # As the build runs it. Left where it is, the library would be taken
        # for built by a later run whose link command is the old one again.
        short = self.linked(4096)
        self.assertRefused(self.check(short), "is aligned to 0x1000")
        self.assertTrue(short.exists(), "removed without being asked")
        self.assertRefused(self.check(short, PAGE, "-DDISCARD=ON"), "is aligned to 0x1000", "has been removed")
        self.assertFalse(short.exists(), "the library that was refused is still there")

        text = self.written("text.so", b"not a library")
        self.assertRefused(self.check(text, PAGE, "-DDISCARD=ON"), "not an ELF file")
        self.assertFalse(text.exists())

        whole = self.linked(16384)
        self.assertEqual(0, self.check(whole, PAGE, "-DDISCARD=ON").returncode)
        self.assertTrue(whole.exists(), "a library that passed was removed")

    def test_a_library_linked_for_16_kb_pages_passes(self):
        done = self.check(self.linked(16384))
        self.assertEqual(0, done.returncode, self.said(done))
        self.assertIn("each aligned to 16384 bytes or more", self.said(done))

    def test_a_library_aligned_to_more_than_is_asked_passes(self):
        done = self.check(self.linked(65536))
        self.assertEqual(0, done.returncode, self.said(done))

    def test_the_page_asked_for_is_the_one_held_to(self):
        # The same library that passes for 16 KB: the number is the caller's.
        self.assertRefused(self.check(self.linked(16384), page=65536), "is aligned to 0x4000", "0x10000")

    def test_one_segment_short_among_others_is_enough(self):
        # 6 is PT_PHDR and 2 is PT_DYNAMIC, whose alignment is 8 in any
        # library and is nobody's business here.
        library = self.written("mixed.so", elf64([(6, 8), (1, 16384), (1, 4096), (2, 8), (1, 16384)]))
        done = self.check(library)
        self.assertRefused(done, "LOAD segment 2 (program header 2) is aligned to 0x1000")
        self.assertNotIn("LOAD segment 1 ", self.said(done))
        self.assertNotIn("LOAD segment 3 ", self.said(done))
        self.assertEqual(0, self.check(self.written("whole.so", elf64([(6, 8), (1, 16384), (2, 8)]))).returncode)

    def test_a_file_that_is_no_elf_is_refused(self):
        self.assertRefused(self.check(self.dir / "one.c"), "not an ELF file")
        self.assertRefused(self.check(self.written("empty.so", b"")), "not an ELF file")
        self.assertRefused(self.check(self.dir / "never-built.so"), "no such file")

    def test_a_library_with_nothing_to_load_is_refused(self):
        self.assertRefused(self.check(self.written("none.so", elf64([]))), "no LOAD segment")
        self.assertRefused(self.check(self.written("others.so", elf64([(6, 8), (2, 8)]))), "no LOAD segment")

    def test_a_file_cut_short_is_refused(self):
        whole = elf64([(1, 16384), (1, 16384)])
        self.assertRefused(self.check(self.written("no-header.so", whole[:40])), "ends inside its header")
        self.assertRefused(self.check(self.written("half-a-table.so", whole[:64 + 56 + 20])),
                           "ends inside its program headers")

    def test_a_32_bit_library_is_said_and_let_through(self):
        # Sixteen bytes that say what the file is; nothing after them is read.
        library = self.written("thirty-two.so", b"\x7fELF" + bytes([1, 1, 1]) + bytes(9))
        done = self.check(library)
        self.assertEqual(0, done.returncode, self.said(done))
        self.assertIn("32-bit", self.said(done))

    def test_an_elf_of_another_byte_order_is_refused(self):
        library = self.written("big-endian.so", b"\x7fELF" + bytes([2, 2, 1]) + bytes(57))
        self.assertRefused(self.check(library), "not a 64-bit little-endian ELF file")


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    unittest.main(verbosity=2)
