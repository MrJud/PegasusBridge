#!/usr/bin/env python3
"""Fails when a module of shared/ ran fewer tests than it declares.

JUnit Jupiter skips a @Test method that returns a value, and says nothing: no
failure, no "skipped", no warning. In Kotlin that happens as soon as an
expression-bodied test ends in something other than Unit —

    @Test fun `zip archives ...`() = runBlocking { ...; tmp.deleteRecursively() }

returns Boolean, so the test stops existing while the suite stays green. The
Gradle output cannot give it away, because the test is simply not in it. The
only place the loss shows is a count: this script compares, per module, the
@Test annotations under src/test with the testcases in the JUnit XML that the
last `./gradlew test` wrote, and names the tests that went missing.

    ./tests/check_test_counts.py [shared-dir]     (run after ./gradlew test)

It builds nothing and reads build/test-results/test as it finds it. A test
JUnit reports as skipped (a disabled one) still counts as run: it was seen,
which is all this is checking. Exit status 0 when every module matches, 1 when
any differs or has no results, 2 when there is nothing to check.

When it was added, on dd636ee, it caught exactly one: hasher declared 67 and
ran 66, the missing one being `zip archives are hashed via their largest entry`
in RomScanPipelineTest — the example above, dead since it was written.
"""

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# @Test, @kotlin.test.Test, @org.junit.jupiter.api.Test — but not @TestFactory,
# @BeforeTest and the like.
TEST_ANNOTATION = re.compile(r"@(?:[\w.]+\.)?Test(?!\w)")
# The function an annotation sits on: the first `fun` after it, with its name
# either plain or backticked (most tests here are named in prose).
FUN_NAME = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(`[^`\n]+`|\w+)")


def code_only(src):
    """The source with comments and the insides of string and char literals
    taken out, so that "@Test" in a KDoc or in an assertion message is not
    counted as a test. Newlines are kept, so line numbers still match.

    Backticked names are copied untouched: they are code, they are the test
    names, and an apostrophe in one (`doesn't crash`) must not open a char
    literal. Kotlin's nested block comments and ${...} templates — code inside
    a string, possibly with strings of its own — are followed properly; the
    rest is as much Kotlin as counting annotations needs, and Java (which has
    no templates and does not nest comments) reads the same way in practice.
    """
    out = []
    n = len(src)
    i = 0
    # What we are inside of. "code" at the bottom is the file itself; a
    # ["tmpl", depth] is the code of a ${...} template, which ends at the brace
    # matching its own, after `depth` inner ones have closed.
    stack = ["code"]

    def keep_newlines(a, b):
        out.append("\n" * src.count("\n", a, b))

    while i < n:
        top = stack[-1]
        c = src[i]
        if top == "str" or top == "raw":
            if top == "str" and c == "\\":
                i += 2
            elif top == "str" and (c == '"' or c == "\n"):
                # A newline means we lost track of an ordinary string; ending it
                # here keeps one mistake from swallowing the rest of the file.
                stack.pop()
                if c == "\n":
                    out.append(c)
                i += 1
            elif top == "raw" and src.startswith('"""', i):
                # A raw string ends at the last quote of a run: """a"""" is a".
                j = i
                while j < n and src[j] == '"':
                    j += 1
                stack.pop()
                i = j
            elif src.startswith("${", i):
                stack.append(["tmpl", 0])
                i += 2
            else:
                if c == "\n":
                    out.append(c)
                i += 1
            continue

        # Code, either the file's own or a template's.
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            keep_newlines(i, j)
            i = j
        elif src.startswith('"""', i):
            out.append('""')
            stack.append("raw")
            i += 3
        elif c == '"':
            out.append('""')
            stack.append("str")
            i += 1
        elif c == "'":
            # 'a', '"', '\'', '\u0041'. Anything that does not close on the same
            # line is not a char literal we understand; let it through as code.
            j = i + 1
            if j < n and src[j] == "\\":
                j += 2
                while j < n and src[j] not in "'\n":
                    j += 1
            else:
                j += 1
            if j < n and src[j] == "'":
                out.append("''")
                i = j + 1
            else:
                out.append(c)
                i += 1
        elif c == "`":
            j = src.find("`", i + 1)
            j = n if j < 0 else j + 1
            out.append(src[i:j])
            i = j
        elif c == "{" and top != "code":
            top[1] += 1
            out.append(c)
            i += 1
        elif c == "}" and top != "code":
            if top[1] == 0:
                stack.pop()  # back inside the string the template belongs to
            else:
                top[1] -= 1
                out.append(c)
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def declared_tests(module):
    """(name, "file:line") for every @Test under module/src/test."""
    found = []
    for path in sorted((module / "src" / "test").rglob("*")):
        if path.suffix not in (".kt", ".java") or not path.is_file():
            continue
        code = code_only(path.read_text(encoding="utf-8"))
        rel = path.relative_to(module.parent)
        for m in TEST_ANNOTATION.finditer(code):
            fun = FUN_NAME.search(code, m.end())
            name = fun.group(1).strip("`") if fun else "?"
            line = code.count("\n", 0, m.start()) + 1
            found.append((name, f"{rel}:{line}"))
    return found


def reported_tests(results):
    """The method name of every testcase in the JUnit XML under `results`."""
    names = []
    for xml in sorted(results.glob("TEST-*.xml")):
        for case in ET.parse(xml).getroot().iter("testcase"):
            # Gradle writes a Jupiter method as "name()"; only the trailing
            # parameter list goes, since a prose name may hold parentheses.
            names.append(re.sub(r"\([^()]*\)$", "", case.get("name", "")))
    return names


def main(argv):
    shared = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    modules = sorted(p for p in shared.iterdir() if (p / "src" / "test").is_dir())
    if not modules:
        print(f"no module with src/test under {shared}", file=sys.stderr)
        return 2

    ok = True
    dropped = False  # fewer ran than declared: the case the hint at the end is about
    print(f"{'module':<10} {'declared':>8} {'ran':>5}")
    for module in modules:
        declared = declared_tests(module)
        results = module / "build" / "test-results" / "test"
        if not results.is_dir():
            print(f"{module.name:<10} {len(declared):>8} {'-':>5}   no results — run ./gradlew test first")
            ok = False
            continue
        ran = reported_tests(results)
        same = len(declared) == len(ran)
        print(f"{module.name:<10} {len(declared):>8} {len(ran):>5}" + ("" if same else "   DIFFERS"))
        if same:
            continue
        ok = False
        dropped = dropped or len(ran) < len(declared)

        # Names say which test it was; the count alone only says that one is gone.
        missing = Counter(name for name, _ in declared) - Counter(ran)
        extra = Counter(ran) - Counter(name for name, _ in declared)
        for name, where in declared:
            if missing[name] > 0:
                missing[name] -= 1
                print(f"    declared, never ran: {where}  {name}")
        for name, count in sorted(extra.items()):
            print(f"    ran, not found declared: {name}" + (f" (x{count})" if count > 1 else ""))

    if dropped:
        print("\nA test that never runs cannot fail. In Kotlin the usual cause is an"
              "\nexpression body that does not end in Unit — write `fun x(): Unit = runBlocking { ... }`.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
