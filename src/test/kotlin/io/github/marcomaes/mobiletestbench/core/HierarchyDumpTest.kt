package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HierarchyDumpTest {

    @Test
    fun `sibling file swaps the extension`() {
        assertEquals(File("/tmp/a/shot-1.xml"), HierarchyDump.siblingFile(File("/tmp/a/shot-1.png")))
        assertEquals(File("/tmp/a/shot.v2.xml"), HierarchyDump.siblingFile(File("/tmp/a/shot.v2.png")))
        assertEquals(File("/tmp/a/shot.xml"), HierarchyDump.siblingFile(File("/tmp/a/shot")))
    }

    @Test
    fun `xml is well formed, escaped and nested`() {
        val root = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), Rect(0, 0, 100, 200))
        val child = UiNode(Platform.ANDROID, "android.widget.Button", mapOf("text" to "A & \"B\" <c>\nx", "bad key" to "z"), Rect(1, 2, 3, 4))
        root.addChild(child)
        val xml = HierarchyDump.toXml(root)
        assertTrue(xml.contains("text=\"A &amp; &quot;B&quot; &lt;c&gt;&#10;x\""))
        assertTrue(xml.contains("x=\"1\" y=\"2\" width=\"3\" height=\"4\""))
        assertTrue(!xml.contains("bad key"))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val button = doc.documentElement.firstChild.let { n -> doc.getElementsByTagName("android.widget.Button").item(0) }
        assertEquals("A & \"B\" <c>\nx", button.attributes.getNamedItem("text").nodeValue)
    }

    @Test
    fun `invalid tag name falls back to node`() {
        val root = UiNode(Platform.IOS, "", emptyMap(), null)
        assertTrue(HierarchyDump.toXml(root).contains("<node/>"))
    }
}
