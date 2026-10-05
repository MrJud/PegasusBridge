#!/usr/bin/env python3
"""Tests check_test_counts.py on small made-up modules, sources and JUnit XML
written to a temporary directory, so that a change to the parser or to the
matching shows here before CI trusts the script with the real suite.

    ./tests/check_test_counts_test.py

The XML is shaped like what Gradle 8.11 writes for JUnit Jupiter: the binary
class name in `classname`, and `name` as "method()" or "method(Types)", as the
display name for a @DisplayName, "repetition 1 of 3" for a @RepeatedTest and the
dynamic test's own name for a @TestFactory. Those shapes were read off a real
run of such tests in a scratch copy of shared/.
"""

import io
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ left in tests/
sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_test_counts as check  # noqa: E402

PKG = "package p\n\nimport kotlin.test.Test\n\n"


class Fixture:
    """A shared/ directory with modules made of the given sources and, unless
    `cases` is None, the testcases their last run reported."""

    def __init__(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def close(self):
        self._tmp.cleanup()

    def module(self, name, sources, cases=()):
        module = self.root / name
        for rel, text in sources.items():
            path = module / "src" / "test" / "kotlin" / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        if cases is None:
            return module
        results = module / "build" / "test-results" / "test"
        results.mkdir(parents=True)
        by_class = {}
        for cls, case in cases:
            by_class.setdefault(cls, []).append(case)
        for cls, names in by_class.items():
            suite = ET.Element("testsuite", name=cls, tests=str(len(names)))
            for case in names:
                ET.SubElement(suite, "testcase", name=case, classname=cls)
            ET.ElementTree(suite).write(results / f"TEST-{cls}.xml", encoding="utf-8")
        return module

    def run(self):
        out = io.StringIO()
        with redirect_stdout(out), redirect_stderr(io.StringIO()):
            status = check.main(["check_test_counts.py", str(self.root)])
        return status, out.getvalue()


def declared(text, name="p/T.kt"):
    """(class, name, line) of each test check_test_counts reads in one file."""
    fixture = Fixture()
    try:
        tests, _ = check.read_sources(fixture.module("m", {name: text}, None))
        return [(t.cls, t.name, int(t.where.rsplit(":", 1)[1])) for t in tests]
    finally:
        fixture.close()


class ParserTest(unittest.TestCase):

    def test_comments_strings_and_char_literals_hold_no_tests(self):
        src = PKG + '''\
/** A KDoc that mentions @Test, /* nested */ and still @Test. */
class T {
    // @Test in a line comment
    @Test fun `doesn't crash`() {
        val s = "@Test in a string, with \\" an escaped quote"
        val raw = """@Test in a raw string ${listOf("@Test").size} and on""""
        val c = '"'
        val q = '\\''
    }

    @Test fun second() {}
}
'''
        self.assertEqual(declared(src), [("p.T", "doesn't crash", 8), ("p.T", "second", 15)])

    def test_a_backticked_name_mentioning_test_is_not_an_annotation(self):
        src = PKG + '''\
class T {
    @Test fun `mentions @Test in its own name`() {}

    @Test fun `parses {} and (a`() {}

    @Test fun last() {}
}

class After {
    @Test fun inAfter() {}
}
'''
        self.assertEqual(declared(src), [
            ("p.T", "mentions @Test in its own name", 6),
            ("p.T", "parses {} and (a", 8),
            ("p.T", "last", 10),
            ("p.After", "inAfter", 14),
        ])

    def test_a_java_test_is_named_by_its_method(self):
        # Java has no `fun`: looking for one named every Java test "?", which
        # then never matched its testcase and failed a suite that had run.
        self.assertEqual(declared('''\
package p;

import org.junit.jupiter.api.Test;

class J {
    @Test
    void plain() {}

    @Test public void withModifier() throws Exception {}
}
''', name="p/J.java"), [("p.J", "plain", 6), ("p.J", "withModifier", 9)])

    def test_an_aliased_import_of_test_is_a_test(self):
        src = ("package p\n\nimport kotlin.test.Test as Check\n"
               "import org.junit.jupiter.api.Test as JTest\n\n"
               "class T {\n    @Check fun aliased() {}\n\n    @JTest fun jupiter() {}\n\n"
               "    @CheckThis fun notOne() {}\n}\n")
        self.assertEqual(declared(src), [("p.T", "aliased", 7), ("p.T", "jupiter", 9)])

    def test_each_test_belongs_to_the_class_around_it(self):
        src = PKG + '''\
class First(private val x: Int = run { 1 }) : Base(), Iface {
    private class Helper(val y: Int)

    @Test fun one() {}

    @Nested inner class Inside {
        @Test fun nested() {}

        @Nested inner class Deeper { @Test fun deeper() {} }
    }

    companion object {
        const val Z = 1
    }

    @Test fun afterCompanion() {}
}

class Second : Base()

object Third {
    @Test fun inObject() {}
}

@Test fun topLevel() {}
'''
        self.assertEqual(declared(src), [
            ("p.First", "one", 8),
            ("p.First$Inside", "nested", 11),
            ("p.First$Inside$Deeper", "deeper", 13),
            ("p.First", "afterCompanion", 20),
            ("p.Third", "inObject", 26),
            ("p.TKt", "topLevel", 29),
        ])

    def test_a_display_name_is_the_name_reported(self):
        src = PKG + '''\
import org.junit.jupiter.api.DisplayName

class T {
    @Test @DisplayName("A pretty \\"name\\" (x)") fun pretty() {}

    @DisplayName(
        "on its own lines"
    )
    @Test
    fun spread() {}

    @Test fun plain() = Unit

    @Test @DisplayName("built ${Z}") fun templated() {}
}
'''
        fixture = Fixture()
        try:
            tests, _ = check.read_sources(fixture.module("m", {"p/T.kt": src}, None))
        finally:
            fixture.close()
        self.assertEqual([t.name for t in tests],
                         ['A pretty "name" (x)', "on its own lines", "plain", "templated"])
        self.assertEqual([t.note is not None for t in tests], [False, False, False, True])


class MatchingTest(unittest.TestCase):

    def setUp(self):
        self.fixture = Fixture()

    def tearDown(self):
        self.fixture.close()

    def test_a_dropped_test_is_found_even_when_another_runs_twice(self):
        # The case summing per module missed: two runs of one inherited test
        # make up for the test JUnit dropped, and the totals agree.
        self.fixture.module("m", {"p/T.kt": PKG + '''\
abstract class Base {
    @Test fun inherited() {}
}

class SubOne : Base()
class SubTwo : Base()

class Holder {
    @Test fun dropped() = listOf(1).isEmpty()
}
'''}, [("p.SubOne", "inherited()"), ("p.SubTwo", "inherited()")])
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn("m                 2     2   1 declared, never ran", out)
        self.assertIn("declared, never ran: m/src/test/kotlin/p/T.kt:13  Holder > dropped", out)
        self.assertIn("extra run: Base > inherited", out)
        self.assertIn("A test that never runs cannot fail.", out)

    def test_extra_runs_alone_do_not_fail(self):
        self.fixture.module("m", {"p/T.kt": PKG + '''\
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.TestFactory

interface WithDefault {
    @Test fun fromInterface() {}
}

class Impl : WithDefault

class T {
    @Test fun withInfo(info: TestInfo) {}

    @Test @DisplayName("ends in (parens)") fun shown() {}

    @Test internal fun hidden() {}

    @RepeatedTest(2) fun repeated() {}

    @TestFactory fun factory() = listOf(dynamicTest("first") {})
}
'''}, [
            ("p.Impl", "fromInterface()"),
            ("p.T", "withInfo(TestInfo)"),
            ("p.T", "ends in (parens)"),
            ("p.T", "hidden$m_test()"),
            ("p.T", "repetition 1 of 2"),
            ("p.T", "repetition 2 of 2"),
            ("p.T", "first"),
        ])
        status, out = self.fixture.run()
        self.assertEqual(status, 0, out)
        self.assertIn("m                 4     7   3 extra", out)
        self.assertIn("extra run: T > repetition 1 of 2", out)
        self.assertIn("extra run: T > first", out)
        self.assertNotIn("never ran", out)

    def test_a_test_is_looked_for_in_its_own_class(self):
        # By name alone the run of First.same would stand in for Second's.
        self.fixture.module("m", {"p/T.kt": PKG + '''\
class First {
    @Test fun same() {}
}

class Second {
    @Test fun same() {}
}
'''}, [("p.First", "same()"), ("p.First", "same()")])
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn("declared, never ran: m/src/test/kotlin/p/T.kt:10  Second > same", out)
        self.assertIn("extra run: First > same", out)

    def test_every_module_is_checked_and_missing_results_fail(self):
        self.fixture.module("a", {"p/A.kt": PKG + "class A {\n    @Test fun a() {}\n}\n"},
                            [("p.A", "a()")])
        self.fixture.module("b", {"p/B.kt": PKG + "class B {\n    @Test fun b() {}\n}\n"}, None)
        status, out = self.fixture.run()
        self.assertEqual(status, 1)
        self.assertIn("a                 1     1\n", out)
        self.assertIn("b                 1     -   no results", out)

    def test_nothing_to_check(self):
        status, _ = self.fixture.run()
        self.assertEqual(status, 2)


if __name__ == "__main__":
    unittest.main()
