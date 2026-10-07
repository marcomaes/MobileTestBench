package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorGenerator
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorQuery
import io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorQueryTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)
    private val android: UiNode = UiAutomatorParser.parse(fixture("android_login.xml"))
    private val ios: UiNode = WdaSourceParser.parse(fixture("ios_login.xml"))

    @Test
    fun `finds elements with mobilewright locators`() {
        val byId = LocatorQuery.find("screen.getByTestId('com.example.app:id/email')", android)
        assertEquals(1, byId.size)
        assertEquals("com.example.app:id/email", byId[0].resourceId)
        assertEquals(1, LocatorQuery.find("""getByTestId("email-input")""", ios).size)
    }

    @Test
    fun `every suggested locator finds its own node`() {
        for (root in listOf(android, ios)) {
            val node = root.walk().first { !it.isRoot && it.children.isEmpty() && (it.resourceId != null || it.identifier != null) }
            LocatorGenerator.suggest(node, root)
                .filter { it.framework == "Mobilewright" || it.strategy.startsWith("xpath") }
                .forEach { suggestion ->
                    val found = LocatorQuery.find(suggestion.expression, root)
                    assertTrue("${suggestion.expression} should find the node", found.any { it === node })
                    assertEquals(suggestion.expression, suggestion.matchCount, found.size)
                }
        }
    }

    @Test
    fun `absolute xpath finds exactly one node`() {
        val node = android.walk().first { it.resourceId == "com.example.app:id/email" }
        assertSame(node, LocatorQuery.find(node.absoluteXPath(), android).single())
    }

    @Test
    fun `no match gives an empty list`() {
        assertTrue(LocatorQuery.find("screen.getByTestId('nope')", android).isEmpty())
        assertTrue(LocatorQuery.find("//does.not.Exist", android).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown syntax is rejected`() {
        LocatorQuery.find("click the button", android)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `broken xpath is rejected`() {
        LocatorQuery.find("//node[", android)
    }

    @Test
    fun `an absolute path of a deeply nested element is found without the xpath engine`() {
        var parent = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)
        val top = parent
        repeat(60) { parent = UiNode(Platform.ANDROID, "android.view.ViewGroup", emptyMap(), Rect(0, 0, 10, 10)).also(parent::addChild) }
        val leaf = UiNode(Platform.ANDROID, "android.widget.Button", mapOf("text" to "deep"), Rect(0, 0, 5, 5)).also(parent::addChild)
        val path = leaf.absoluteXPath()
        assertTrue(path.count { it == '/' } > 60)
        assertSame(leaf, LocatorQuery.find(path, top).single())
        // A segment that does not exist finds nothing.
        assertTrue(LocatorQuery.find(path.replace("Button[1]", "Button[2]"), top).isEmpty())
    }
}
