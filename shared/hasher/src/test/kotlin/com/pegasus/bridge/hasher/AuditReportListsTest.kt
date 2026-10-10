package com.pegasus.bridge.hasher

import com.pegasus.bridge.core.RcConsoles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Holds the lists of `tools/audit_report.py` to the tables they are copies
 * of.
 *
 * The script judges an audit's table with no list of right answers: a hash
 * in a collection nobody can hash for is junk whatever it is, and a file
 * hashed by its name is junk outside an arcade collection. Which
 * collections those are is the console table's to say, and the script,
 * which reads no Kotlin, kept lists of its own. Nothing held them to the
 * table, and they fell behind it: six names the table refuses were not on
 * the script's list, `windows`, `pc` and `cdtv` among them, so a hash under
 * one of those would have passed for none, which is the one thing that rule
 * is there to catch. Three of the ledger's states were missing from the
 * order the report's columns are written in.
 *
 * So this reads the script, as [com.pegasus.bridge.core] reads the C files
 * of rcheevos, and a row or a state added on one side and not on the other
 * fails here.
 */
class AuditReportListsTest {

    private val script: String by lazy {
        val file = File("../tools/audit_report.py")
        assertTrue(file.isFile, "the audit's report script is not at ${file.absolutePath}")
        file.readText()
    }

    /** The strings of the list or set the script assigns to [name]. */
    private fun listed(name: String): List<String> {
        val body = Regex("""(?m)^$name = [\[{]([^\]}]*)[\]}]""").find(script)?.groupValues?.get(1)
            ?: fail("audit_report.py assigns no list to $name")
        return Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** A name as the script's `platform_key` spells it: lower case, letters and digits only. */
    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private fun names(rows: List<RcConsoles.Row>): Set<String> =
        rows.flatMap { listOf(it.key) + it.spellings }.map(::key).toSet()

    @Test fun `the arcade collections of the script are the arcade rows of the console table`() {
        val arcade = RcConsoles.ROWS.filter { it is RcConsoles.Hashable && it.arcade }
        assertTrue(arcade.isNotEmpty())
        assertEquals(names(arcade).sorted(), listed("ARCADE").sorted())
    }

    @Test fun `the collections with no algorithm are the rows of the console table that cannot be hashed`() {
        val refused = RcConsoles.ROWS.filter { it !is RcConsoles.Hashable }
        assertTrue(refused.isNotEmpty())
        assertEquals(names(refused).sorted(), listed("NO_ALGORITHM").sorted())
        // And no name is on both lists, or on one of them and a row that is hashed otherwise.
        val hashed = names(RcConsoles.ROWS.filter { it is RcConsoles.Hashable && !it.arcade })
        assertEquals(emptySet(), (listed("ARCADE") + listed("NO_ALGORITHM")).toSet() intersect hashed)
    }

    @Test fun `the order of the report's columns has every state of the ledger, once`() {
        val order = listed("STATE_ORDER")
        assertEquals(order.distinct(), order, "a state is in the order twice")
        assertEquals(ScanLedger.State.values().map { it.name }.sorted(), order.sorted())
    }
}
