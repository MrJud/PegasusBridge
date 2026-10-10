#!/usr/bin/env python3
"""Tests the checks around the native build on things made to fail them.

    ./tests/native_checks_test.py [tools-dir]

Three checks stand between the C and the library the daemon loads, and what
each refuses is not seen in an ordinary run, where there is nothing to refuse:

  native_lib_check.sh    Given small libraries linked here: one as build.sh
                         links, one that asks for a newer glibc through a
                         symbol, one that asks for it with no symbol at all,
                         one that exports a name too many. And DLLs, where
                         there is the compiler that makes them: one as
                         build.sh links, one that needs a DLL Windows does
                         not have of its own, one that exports too much.
  native/build.sh        Given a compiler that cannot say what the library is
                         compiled from, or leaves a source out of the answer.
                         The build must stop before it writes anything.
  native_repro_test.py   Given, for its sanitized program, one built with no
                         sanitizer. It must say so and run no row.

It needs what the build needs: gcc and binutils, and JAVA_HOME naming a JDK.
tools-dir is where `native/build.sh <out> --tools=<tools-dir>` put rahash_cli;
with no argument it is built first, into a temporary directory.

The tests on DLLs need x86_64-w64-mingw32-gcc and are skipped, each with a
line that says so, where it is not on PATH. They are the only ones that can
run on Windows, where the rest have no glibc to ask about:

    python -m unittest -v native_checks_test.DllCheckTest     (from tests/)
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
SHARED = TESTS.parent
REPOSITORY = SHARED.parent
LIB_CHECK = TESTS / "native_lib_check.sh"
REPRO_TEST = TESTS / "native_repro_test.py"
BUILD = SHARED / "native" / "build.sh"
SOURCE_LIST = REPOSITORY / "hasher" / "src" / "main" / "cpp" / "rahasher.sources"

# Set in main() from the command line; None is "build them".
TOOLS = None

# The compiler native/build.sh makes rahasher.dll with, on Windows and
# elsewhere, or None.
MINGW = shutil.which("x86_64-w64-mingw32-gcc")

# By the place it is found in on PATH. Windows, asked to start `bash`, looks
# in its own folders first, and what is there starts a Linux beside Windows
# that has never heard of these files.
BASH = shutil.which("bash") or "bash"


def run(command, env=None):
    done = subprocess.run([str(word) for word in command], env=env, cwd=str(SHARED),
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT)
    return done.returncode, done.stdout.decode("utf-8", "replace")


class Scratch(unittest.TestCase):
    """A test with a directory of its own."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory(prefix="native-checks-")
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)


class LibraryCheck(Scratch):
    """What the tests of native_lib_check.sh on either kind of library need."""

    def expected_exports(self):
        """The names the check holds a library to, read from the check, so
        that the day they change they change in one place."""
        found = re.search(r'^expected_exports="([^"]*)"', LIB_CHECK.read_text(encoding="utf-8"),
                          re.MULTILINE)
        self.assertIsNotNone(found, "native_lib_check.sh no longer has expected_exports=\"…\"")
        return found.group(1).split()

    def check(self, library):
        return run([BASH, LIB_CHECK, library])


class LibCheckTest(LibraryCheck):
    """native_lib_check.sh on libraries of one small function each."""

    def library(self, name, body="", link=(), exports=None):
        """A library as build.sh compiles one: the names in `exports` (the
        expected ones unless given), each a function that calls memcpy, which
        is what makes the real library ask for glibc 2.14."""
        exports = self.expected_exports() if exports is None else exports
        source = self.tmp / f"{name}.c"
        source.write_text(
            "#include <string.h>\n"
            '#define SHOWN __attribute__((visibility("default")))\n'
            + "".join(f"SHOWN void* {export}(void* to, const void* from, size_t size)\n"
                      "{ return memcpy(to, from, size); }\n" for export in exports)
            + body, encoding="utf-8")
        library = self.tmp / f"{name}.so"
        status, output = run(["gcc", "-std=gnu11", "-O2", "-fPIC", "-fvisibility=hidden",
                              "-shared", "-o", library, source, *link])
        self.assertEqual(status, 0, f"gcc could not link the library of this test:\n{output}")
        return library

    def test_a_library_linked_as_the_build_links_passes(self):
        status, output = self.check(self.library("good"))
        self.assertEqual(status, 0, output)
        self.assertIn("ok   needs glibc 2.14 (the floor is 2.14)", output)
        self.assertIn(f"ok   exports {len(self.expected_exports())} function(s) and nothing else",
                      output)

    def test_a_symbol_of_a_newer_glibc_is_refused_and_named(self):
        # explicit_bzero came with glibc 2.25: newer than the floor, and old
        # enough to be on any system this runs on.
        library = self.library("newer", body="SHOWN void wipe(void* at) { explicit_bzero(at, 8); }\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn("FAIL needs glibc 2.25, and the floor is 2.14", output)
        self.assertRegex(output, r"\(GLIBC_2\.25\) +(__)?explicit_bzero")

    def test_packed_relocations_are_refused_though_no_symbol_shows_them(self):
        # Every symbol of this library is one the first library has. What
        # differs is a line among its version references, and a loader older
        # than 2.36 refuses it for that line.
        library = self.library("packed", link=["-Wl,-z,pack-relative-relocs"],
                               body="static int word; SHOWN int* where = &word;\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn("FAIL needs glibc 2.36, and the floor is 2.14", output)
        self.assertIn("GLIBC_ABI_DT_RELR", output)

    def test_a_name_too_many_is_refused(self):
        library = self.library("wide", body="SHOWN int one_more(void) { return 1; }\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn(f"FAIL exports {len(self.expected_exports()) + 1} symbols", output)
        self.assertIn("exported  one_more", output)

    def test_a_name_too_few_is_refused(self):
        library = self.library("narrow", exports=["some_other_name"])
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn("FAIL exports 1 symbols", output)

    def test_a_library_that_asks_glibc_for_nothing_is_refused(self):
        library = self.library("alone", exports=[], link=["-nostdlib"],
                               body="SHOWN int alone(void) { return 1; }\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn("FAIL no version of glibc is asked for", output)

    def test_what_is_not_a_library_is_a_usage_error(self):
        text = self.tmp / "text.so"
        text.write_text("not a library\n", encoding="utf-8")
        for arguments in ([text], [self.tmp / "missing.so"], []):
            with self.subTest(arguments):
                status, output = run([BASH, LIB_CHECK, *arguments])
                self.assertEqual(status, 2, output)


@unittest.skipIf(MINGW is None, "no x86_64-w64-mingw32-gcc on PATH to make a DLL with")
class DllCheckTest(LibraryCheck):
    """native_lib_check.sh on DLLs of one small function each. They are made
    and read here and never loaded, so this runs wherever the compiler does."""

    # The flags of build.sh for a DLL, but for the two that only make the same
    # file come out twice.
    AS_BUILT = ("-static", "-static-libgcc")

    def library(self, name, body="", link=AS_BUILT, exports=None):
        """A DLL as build.sh compiles one: the names in `exports` (the
        expected ones unless given), each marked for the DLL to show as
        jni_md.h has JNIEXPORT mark the two real ones."""
        exports = self.expected_exports() if exports is None else exports
        source = self.tmp / f"{name}.c"
        source.write_text(
            "#include <string.h>\n"
            "#define SHOWN __declspec(dllexport)\n"
            + "".join(f"SHOWN void* {export}(void* to, const void* from, size_t size)\n"
                      "{ return memcpy(to, from, size); }\n" for export in exports)
            + body, encoding="utf-8")
        library = self.tmp / f"{name}.dll"
        status, output = run([MINGW, "-std=gnu11", "-O2", "-shared", "-o", library, source, *link])
        self.assertEqual(status, 0, f"{MINGW} could not link the DLL of this test:\n{output}")
        return library

    def test_a_dll_linked_as_the_build_links_passes(self):
        status, output = self.check(self.library("good"))
        self.assertEqual(status, 0, output)
        self.assertRegex(output, r"ok   needs \d+ DLL\(s\), all of them Windows' own")
        self.assertRegex(output, r"(?i)\n +kernel32\.dll\n")
        self.assertIn(f"ok   exports {len(self.expected_exports())} function(s) and nothing else",
                      output)

    def test_a_dll_that_is_not_windows_own_is_refused_and_named(self):
        # The windows and the sounds are Windows' own as well, and are not
        # the kernel or the C library: a hasher that needs them is one that
        # has come to do something else. What -static keeps out, libgcc and
        # the threads of mingw-w64, cannot be brought in on purpose with
        # every build of the compiler; a name is refused the same way
        # whatever it is.
        library = self.library("windowed", link=[*self.AS_BUILT, "-luser32"],
                               body="__declspec(dllimport) int __stdcall MessageBeep(unsigned kind);\n"
                                    "SHOWN int beep(void) { return MessageBeep(0); }\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn("FAIL needs 1 DLL(s) that Windows does not have of its own", output)
        self.assertRegex(output, r"(?i)\n +user32\.dll\n")

    def test_a_name_too_many_in_a_dll_is_refused(self):
        library = self.library("wide", body="SHOWN int one_more(void) { return 1; }\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn(f"FAIL exports {len(self.expected_exports()) + 1} symbols", output)
        self.assertIn("exported  one_more", output)

    def test_a_dll_with_nothing_marked_shows_everything_and_is_refused(self):
        # With no function marked, the linker shows every one there is: the
        # two the JVM looks up are then among them by their names alone, and
        # so is all of rcheevos.
        library = self.library("unmarked", exports=[],
                               body="".join(f"int {name}(void) {{ return 1; }}\n"
                                            for name in [*self.expected_exports(), "md5_init"]))
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        # How many it shows is the linker's affair: some builds of it show
        # what the C library put in beside these.
        self.assertRegex(output, r"FAIL exports \d+ symbols")
        self.assertIn("exported  md5_init", output)

    def test_a_dll_that_puts_text_together_with_a_secure_function_is_refused(self):
        # What rcheevos did for its messages, through vsprintf_s: text that
        # does not fit ends the process. The same with snprintf is cut short,
        # and passes.
        library = self.library("secure", body="#include <stdio.h>\n"
                               "int say(char* to, int n) { return sprintf_s(to, 8, \"%d\", n); }\n")
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertEqual(output.count("FAIL"), 1, output)
        self.assertIn("FAIL calls 1 function(s) that end the process on text too long for its buffer", output)
        self.assertRegex(output, r"\n +\S*printf_s\n")

        library = self.library("cut", body="#include <stdio.h>\n"
                               "int say(char* to, int n) { return snprintf(to, 8, \"%d\", n); }\n")
        status, output = self.check(library)
        self.assertEqual(status, 0, output)
        self.assertRegex(output, r"ok   calls \d+ function\(s\) of those, none that ends the process")

    def test_a_name_too_few_in_a_dll_is_refused(self):
        library = self.library("narrow", exports=["some_other_name"])
        status, output = self.check(library)
        self.assertEqual(status, 1, output)
        self.assertIn("FAIL exports 1 symbols", output)

    def test_what_begins_as_a_dll_and_is_none_is_a_usage_error(self):
        text = self.tmp / "text.dll"
        text.write_text("MZ and no more\n", encoding="utf-8")
        status, output = self.check(text)
        self.assertEqual(status, 2, output)


class BuildListTest(Scratch):
    """native/build.sh with a compiler whose -MM cannot be relied on. The
    compiler is a script named gcc, first on PATH, that answers -MM as the
    test says and hands everything else to the real one."""

    def build_with(self, on_mm):
        real = shutil.which("gcc")
        self.assertIsNotNone(real, "there is no gcc to stand in front of")
        shim = self.tmp / "bin"
        shim.mkdir()
        (shim / "gcc").write_text(
            "#!/bin/sh\n"
            f'for word in "$@"; do if [ "$word" = -MM ]; then {on_mm}; fi; done\n'
            f'exec "{real}" "$@"\n', encoding="utf-8")
        (shim / "gcc").chmod(0o755)
        env = dict(os.environ)
        env["PATH"] = f"{shim}{os.pathsep}{env['PATH']}"
        env["REAL_GCC"] = real
        out = self.tmp / "out"
        status, output = run(["bash", BUILD, out], env=env)
        written = sorted(path.name for path in out.iterdir()) if out.is_dir() else []
        return status, output, written

    def test_a_compiler_that_cannot_list_ends_the_build_with_nothing_written(self):
        status, output, written = self.build_with('echo "gcc: no -MM here" >&2; exit 1')
        self.assertEqual(status, 1, output)
        self.assertIn("the compiler could not list what the library is compiled from", output)
        self.assertEqual(written, [], output)

    def test_a_list_with_nothing_in_it_ends_the_build_though_the_compiler_said_nothing(self):
        status, output, written = self.build_with("exit 0")
        self.assertEqual(status, 1, output)
        self.assertIn("the compiler's list of what the library is compiled from lacks", output)
        self.assertEqual(written, [], output)

    def test_a_list_that_lacks_one_source_ends_the_build_and_names_it(self):
        names = [line.partition("#")[0].strip()
                 for line in SOURCE_LIST.read_text(encoding="utf-8").splitlines()]
        last = [name for name in names if name][-1]
        base = last.rsplit("/", 1)[-1].replace(".", r"\.")
        # The real list, with every word that ends in that file's name gone.
        status, output, written = self.build_with(
            f"\"$REAL_GCC\" \"$@\" | sed 's#[^ ]*/{base}##g'; exit 0")
        self.assertEqual(status, 1, output)
        self.assertIn(f"compiled from lacks hasher/src/main/cpp/{last}", output)
        self.assertEqual(written, [], output)


class ReproTestTest(Scratch):
    """native_repro_test.py, handed a sanitized program that is not one."""

    def plain_program(self):
        if TOOLS is not None:
            return TOOLS / "rahash_cli"
        built = self.tmp / "built"
        status, output = run(["bash", BUILD, built / "out", f"--tools={built}"])
        self.assertEqual(status, 0, f"native/build.sh could not build the tools:\n{output}")
        return built / "rahash_cli"

    def test_a_program_built_with_no_sanitizer_is_refused_before_any_row(self):
        plain = self.plain_program()
        tools = self.tmp / "tools"
        tools.mkdir()
        shutil.copy2(plain, tools / "rahash_cli")
        shutil.copy2(plain, tools / "rahash_cli_sanitized")
        status, output = run([sys.executable, REPRO_TEST, tools])
        self.assertEqual(status, 2, output)
        self.assertIn("rahash_cli_sanitized", output)
        self.assertIn("is not built with -fsanitize=address and -fsanitize=undefined", output)
        self.assertIn("No row was run", output)
        self.assertNotIn("must pass", output)
        self.assertNotIn("known gaps", output)


def main(argv):
    global TOOLS
    if len(argv) > 2 or (len(argv) == 2 and argv[1].startswith("-")):
        print("usage: native_checks_test.py [tools-dir]", file=sys.stderr)
        return 2
    if len(argv) == 2:
        TOOLS = Path(argv[1]).resolve()
    return 0 if unittest.main(argv=argv[:1], exit=False).result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
