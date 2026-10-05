#!/usr/bin/env python3
"""Fails when a test declared in a module of shared/ never ran.

JUnit Jupiter skips a @Test method that returns a value, and says nothing: no
failure, no "skipped", no warning. In Kotlin that happens as soon as an
expression-bodied test ends in something other than Unit —

    @Test fun `zip archives ...`() = runBlocking { ...; tmp.deleteRecursively() }

returns Boolean, so the test stops existing while the suite stays green. The
Gradle output cannot give it away, because the test is simply not in it. This
script reads every @Test under src/test, works out the class it belongs to, and
looks for that class and method among the testcases in the JUnit XML that the
last `./gradlew test` wrote. A declared test with no testcase is named, with its
file and line, and fails the check.

    ./tests/check_test_counts.py [shared-dir]     (run after ./gradlew test)

It goes by identity, not by totals, because the totals do not have to agree. A
@RepeatedTest, a @TestFactory or a parameterised test runs as several testcases
and declares no @Test; a @Test in an abstract class or an interface runs once
for every class that inherits it. Those extra runs are listed and fail nothing.
When this script compared sums per module, one of them could hide a test JUnit
had dropped.

The XML names the class each testcase ran in (its binary name, Outer$Inner for
a nested or @Nested class) and the method as "name(Params)". The sources give
the class around each annotation, objects and companion objects included, and
the classes it extends, so an inherited test is matched to the class that
declares it. A method with @DisplayName("...") is reported under that text,
which is read from the source when it is a plain string literal. `import
kotlin.test.Test as Check` makes `@Check` a test too. A test JUnit reports as
skipped (a disabled one) still counts as run: it was seen, which is all this is
checking.

What it does not follow: a display name built from constants or a template, a
@DisplayNameGeneration or a generator set in junit-platform.properties, or a
@JvmName on the test. Each of those changes the name Gradle reports, and the
test then shows up as never ran, beside an extra run under its real name.

It builds nothing and reads build/test-results/test as it finds it. Exit status
0 when every declared test ran, 1 when one did not or a module has no results,
2 when there is nothing to check. tests/check_test_counts_test.py tests it.

When it was added, on dd636ee, it caught exactly one: hasher declared 67 and
ran 66, the missing one being `zip archives are hashed via their largest entry`
in RomScanPipelineTest — the example above, dead since it was written.
"""

import re
import sys
import xml.etree.ElementTree as ET
from bisect import bisect_left
from collections import Counter, namedtuple
from pathlib import Path

# The function an annotation sits on: the first `fun` after it, with its name
# either plain or backticked (most tests here are named in prose).
FUN_NAME = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(`[^`\n]+`|\w+)")
# The same for a Java test, which JUnit requires to return void.
JAVA_METHOD_NAME = re.compile(r"\bvoid\s+(\w+)\s*\(")
FUN = re.compile(r"\bfun\b")
# A class, interface or object, and its name; a companion object may have none.
# `Foo::class` is not one, and neither is `object : Listener { ... }`.
DECL = re.compile(r"(?<![\w:])(?:companion\s+object\b(?:[ \t]+(`[^`]*`|\w+))?"
                  r"|(?:class|interface|object)\s+(`[^`]*`|\w+))")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
TEST_ALIAS = re.compile(r"^\s*import\s+[\w.]+\.Test\s+as\s+(\w+)", re.M)
DISPLAY_NAME = re.compile(r"@(?:[\w.]+\.)?DisplayName\b")
# Only a plain literal can be read: no template, no concatenation.
DISPLAY_LITERAL = re.compile(r'\s*\(\s*(?:value\s*=\s*)?"((?:[^"\\$\n]|\\.)*)"\s*\)')
# A class header, between its name and its body: type parameters, a primary
# constructor with modifiers or annotations, then `: Base(), Iface by x`.
CONSTRUCTOR = re.compile(r"[ \t]*(?:(?:@[\w.]+|private|protected|internal|public)\s+)*constructor\b")
SUPERTYPE = re.compile(r"\s*([\w.]+)")
DELEGATE = re.compile(r"\s+by\s+[\w.]+")
WHERE = re.compile(r"\s*where\s[^;{}=]*")
SPACE = re.compile(r"\s*")
HSPACE = re.compile(r"[ \t]*")
# Gradle writes a Jupiter method as "name(Types)"; only the trailing parameter
# list goes, since a prose name may hold parentheses.
PARAMS = re.compile(r"\([^()]*\)$")

Declared = namedtuple("Declared", "cls name where note")


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


def masked(code):
    """`code` with the inside of every backticked name blanked out, at the same
    length. The structure is read from this, the names from `code` at the same
    offsets: `mentions @Test in its name` is a name, not an annotation, and
    `parses {} and (a)` must not open a block."""
    return re.sub(r"`[^`]*`", lambda m: "`" + "_" * (len(m.group()) - 2) + "`", code)


def past_brackets(skel, i):
    """The index just past the bracket matching the ( or < at skel[i]."""
    opening = skel[i]
    closing = ")" if opening == "(" else ">"
    depth = 0
    for j in range(i, len(skel)):
        c = skel[j]
        if c == opening:
            depth += 1
        elif c == closing and skel[j - 1] != "-":  # the > of -> closes nothing
            depth -= 1
            if depth == 0:
                return j + 1
        elif opening == "<" and c in "{};":
            return j  # not type parameters after all
    return len(skel)


def class_header(skel, i):
    """The simple names of the types a class extends, and where its header ends,
    for a class whose name ends at skel[i].

    Type parameters and a primary constructor are looked for on the name's own
    line only, since a class with no body may be followed by anything on the
    next; the `:` of the supertypes may be on a line of its own."""
    supers = []
    j = HSPACE.match(skel, i).end()
    if skel.startswith("<", j):
        j = past_brackets(skel, j)
    m = CONSTRUCTOR.match(skel, j)
    if m:
        j = m.end()
    j = HSPACE.match(skel, j).end()
    if skel.startswith("(", j):
        j = past_brackets(skel, j)
    k = SPACE.match(skel, j).end()
    if not skel.startswith(":", k):
        return supers, j
    while True:
        m = SUPERTYPE.match(skel, k + 1)
        if not m:
            return supers, j
        supers.append(m.group(1).rsplit(".", 1)[-1])
        j = m.end()
        for bracket in "<(":
            if skel.startswith(bracket, j):
                j = past_brackets(skel, j)
        m = DELEGATE.match(skel, j)
        if m:
            j = m.end()
            if skel.startswith("(", j):
                j = past_brackets(skel, j)
        k = SPACE.match(skel, j).end()
        if not skel.startswith(",", k):
            return supers, j


def decl_name(code, m):
    """The name a DECL match gives, unmasked; a companion object without one is
    called Companion, as the compiler calls it."""
    for group in (2, 1):
        if m.group(group) is not None:
            return code[m.start(group):m.end(group)].strip("`")
    return "Companion"


def class_bodies(skel, code):
    """(start, end, path) for the body of every class, interface and object,
    where path is the names from the outermost one in, as in Outer$Inner.

    A block is a class body when the last declaration since the previous
    statement ends, header and all, right where the block opens: `class A(val
    x: Int) : B() {` is one, `fun f() {` is not, and neither is the `{` after
    a class with no body of its own. Braces inside parentheses are lambdas."""
    bodies = []
    stack = [{"path": [], "parens": 0, "header": 0}]
    for m in re.finditer(r"[(){};]", skel):
        c, i = m.group(), m.start()
        top = stack[-1]
        if c == "(":
            top["parens"] += 1
        elif c == ")":
            top["parens"] = max(0, top["parens"] - 1)
        elif c == ";":
            if not top["parens"]:
                top["header"] = i + 1
        elif c == "{":
            name = None
            if not top["parens"]:
                last = None
                for last in DECL.finditer(skel, top["header"], i):
                    pass
                if last:
                    rest = skel[class_header(skel, last.end())[1]:i]
                    if not rest.strip() or WHERE.fullmatch(rest):
                        name = decl_name(code, last)
            stack.append({"path": top["path"] + [name] if name else top["path"],
                          "parens": 0, "header": i + 1, "start": i, "is_class": bool(name)})
        else:
            if len(stack) > 1:
                done = stack.pop()
                if done["is_class"]:
                    bodies.append((done["start"], i, done["path"]))
            if not stack[-1]["parens"]:
                stack[-1]["header"] = i + 1
    return bodies


def enclosing(bodies, at):
    """The path of the innermost class body around offset `at`, or []."""
    best = None
    for start, end, path in bodies:
        if start < at < end and (best is None or start > best[0]):
            best = (start, path)
    return best[1] if best else []


def display_name(src, skel, at):
    """The text of the @DisplayName at skel offset `at`, or None if it is not a
    plain string literal. The literal is gone from `skel`, so it is read from
    the same line of `src`: code_only keeps the lines where they were."""
    line = skel.count("\n", 0, at)
    line_start = skel.rfind("\n", 0, at) + 1
    nth = len(DISPLAY_NAME.findall(skel, line_start, at))
    src_line = 0
    for _ in range(line):
        src_line = src.index("\n", src_line) + 1
    found = list(DISPLAY_NAME.finditer(src, src_line))
    if nth >= len(found):
        return None
    m = DISPLAY_LITERAL.match(src, found[nth].end())
    if not m:
        return None
    escapes = {"n": "\n", "t": "\t", "r": "\r", "b": "\b"}
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)",
                  lambda e: chr(int(e.group(1)[1:], 16)) if len(e.group(1)) == 5
                  else escapes.get(e.group(1), e.group(1)),
                  m.group(1))


def read_sources(module):
    """The tests declared under module/src/test, each a Declared(class, name,
    "file:line", note), and the supertypes of every class declared there, by
    binary name, as simple names still to be resolved."""
    tests = []
    supers = {}
    for path in sorted((module / "src" / "test").rglob("*")):
        if path.suffix not in (".kt", ".java") or not path.is_file():
            continue
        src = path.read_text(encoding="utf-8")
        code = code_only(src)
        skel = masked(code)
        rel = path.relative_to(module.parent)
        package = PACKAGE.search(skel)
        prefix = package.group(1) + "." if package else ""
        # Top-level functions land in FileKt, where JUnit does not look.
        file_class = prefix + path.stem[:1].upper() + path.stem[1:] + "Kt"
        bodies = class_bodies(skel, code)

        for m in DECL.finditer(skel):
            binary = prefix + "$".join(enclosing(bodies, m.start()) + [decl_name(code, m)])
            supers[binary] = class_header(skel, m.end())[0]

        # @Test, @kotlin.test.Test, @org.junit.jupiter.api.Test and any name
        # Test is imported as — but not @TestFactory, @BeforeTest and the like.
        names = ["Test"] + TEST_ALIAS.findall(skel)
        annotation = re.compile(r"@(?:[\w.]+\.)?(?:%s)(?!\w)" % "|".join(names))
        funs = [f.start() for f in FUN.finditer(skel)]
        name_of = JAVA_METHOD_NAME if path.suffix == ".java" else FUN_NAME
        for m in annotation.finditer(skel):
            fun = name_of.search(skel, m.end())
            name = code[fun.start(1):fun.end(1)].strip("`") if fun else "?"
            line = code.count("\n", 0, m.start()) + 1
            path_in = enclosing(bodies, m.start())
            cls = prefix + "$".join(path_in) if path_in else file_class

            # The annotations of this function, wherever @Test is among them:
            # back to the previous statement or the previous function.
            note = None
            if fun:
                start = max(skel.rfind(c, 0, m.start()) for c in "{};") + 1
                previous = bisect_left(funs, m.start()) - 1
                if previous >= 0:
                    start = max(start, funs[previous] + 3)
                shown = DISPLAY_NAME.search(skel, start, fun.start())
                if shown:
                    text = display_name(src, skel, shown.start())
                    if text is None:
                        note = "its @DisplayName is not a plain string, so its reported name is unknown"
                    else:
                        name = text
            tests.append(Declared(cls, name, f"{rel}:{line}", note))
    return tests, supers


def resolve_parents(supers):
    """The supertypes of each class as binary names of classes in the same
    sources: the one in the same package, else the only one so named. Anything
    else (a JDK or library type) is not followed."""
    by_simple = {}
    for binary in supers:
        by_simple.setdefault(re.split(r"[.$]", binary)[-1], []).append(binary)
    parents = {}
    for binary, names in supers.items():
        package = binary.rpartition(".")[0]
        resolved = []
        for simple in names:
            found = by_simple.get(simple, [])
            here = [b for b in found if b.rpartition(".")[0] == package]
            if here or len(found) == 1:
                resolved.append((here or found)[0])
        parents[binary] = resolved
    return parents


def ancestry(cls, parents):
    """`cls` and then every class it extends, nearest first."""
    order, queue = [], [cls]
    while queue:
        c = queue.pop(0)
        if c not in order:
            order.append(c)
            queue.extend(parents.get(c, []))
    return order


def reported_tests(results):
    """(classname, name) for every testcase in the JUnit XML under `results`."""
    cases = []
    for xml in sorted(results.glob("TEST-*.xml")):
        for case in ET.parse(xml).getroot().iter("testcase"):
            cases.append((case.get("classname", ""), case.get("name", "")))
    return cases


def attribute(cls, name, declared, parents, module):
    """The declared (class, name) a testcase ran as, or its own if none. The
    name may be a display name as it stands, or "method(Types)"; an internal
    method also carries the module's mangling, "method$core_test()". The class
    is the one it ran in or, for an inherited test, the one that declares it."""
    plain = PARAMS.sub("", name)
    names = (name, plain, plain.removesuffix(f"${module}_test"))
    for c in ancestry(cls, parents):
        for n in names:
            if (c, n) in declared:
                return c, n
    return cls, plain


def short(cls):
    return cls.rpartition(".")[2]


def main(argv):
    shared = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    modules = sorted(p for p in shared.iterdir() if (p / "src" / "test").is_dir())
    if not modules:
        print(f"no module with src/test under {shared}", file=sys.stderr)
        return 2

    ok = True
    dropped = False  # a declared test never ran: the case the hint at the end is about
    print(f"{'module':<10} {'declared':>8} {'ran':>5}")
    for module in modules:
        tests, supers = read_sources(module)
        results = module / "build" / "test-results" / "test"
        if not results.is_dir():
            print(f"{module.name:<10} {len(tests):>8} {'-':>5}   no results — run ./gradlew test first")
            ok = False
            continue
        cases = reported_tests(results)
        parents = resolve_parents(supers)
        declared = Counter((t.cls, t.name) for t in tests)
        ran = Counter(attribute(c, n, declared, parents, module.name) for c, n in cases)
        missing = declared - ran
        extra = ran - declared

        status = ""
        if missing:
            status = f"   {sum(missing.values())} declared, never ran"
        elif extra:
            status = f"   {sum(extra.values())} extra (repeated, dynamic or inherited runs; not an error)"
        print(f"{module.name:<10} {len(tests):>8} {len(cases):>5}{status}")
        if missing:
            ok = False
            dropped = True
            for t in tests:
                if missing[(t.cls, t.name)] > 0:
                    missing[(t.cls, t.name)] -= 1
                    print(f"    declared, never ran: {t.where}  {short(t.cls)} > {t.name}"
                          + (f"  ({t.note})" if t.note else ""))
        for (cls, name), count in sorted(extra.items()):
            print(f"    extra run: {short(cls)} > {name}" + (f" (x{count})" if count > 1 else ""))

    if dropped:
        print("\nA test that never runs cannot fail. In Kotlin the usual cause is an"
              "\nexpression body that does not end in Unit — write `fun x(): Unit = runBlocking { ... }`.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
