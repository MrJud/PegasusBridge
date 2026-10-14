package com.pegasus.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The router's intent filter, as the manifest declares it.
 *
 * A browser puts BROWSABLE on every intent it sends for a page, and an intent
 * matches only a filter that lists each of its categories. Left out of the
 * filter, it keeps every web page from reaching the router at all; the
 * refusals in CredentialVerbs and GameNativeLaunch stay behind it. What the
 * theme and Pegasus send, a VIEW of a `pegasus-data:` URI and no category of
 * its own, still has to match.
 */
class RouterManifestTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.names(tag: String): Set<String> =
        children(tag).map { it.getAttributeNS(android, "name") }.toSet()

    private fun routerFilters(): List<Element> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity").let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }
        val router = activities.single { it.getAttributeNS(android, "name") == ".DataLayerRouter" }
        return router.children("intent-filter")
    }

    @Test fun `the router's filter takes no BROWSABLE`() {
        val filters = routerFilters()
        assertEquals(1, filters.size)
        for (filter in filters)
            assertTrue("a web page could reach the router",
                       "android.intent.category.BROWSABLE" !in filter.names("category"))
    }

    @Test fun `what a theme and Pegasus send still matches it`() {
        val filter = routerFilters().single()
        assertEquals(setOf("android.intent.action.VIEW"), filter.names("action"))
        assertEquals(setOf("android.intent.category.DEFAULT"), filter.names("category"))
        assertEquals(listOf("pegasus-data"),
                     filter.children("data").map { it.getAttributeNS(android, "scheme") })
    }
}
