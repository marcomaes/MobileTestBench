package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRecorderTest {

    private val ios: UiNode = WdaSourceParser.parse(
        javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )

    private fun recorder() = ScriptRecorder(Platform.IOS)

    @Test
    fun `a tap becomes a tap on the best unique locator`() {
        val r = recorder()
        r.tap(ios, 100, 220) // email text field at 20,200 350x44
        assertEquals("await screen.getByTestId('email-input').tap();", r.code())
    }

    @Test
    fun `typing after a tap turns it into fill and backspace edits it`() {
        val r = recorder()
        r.tap(ios, 100, 220)
        r.type("a"); r.type("b"); r.type("c")
        assertEquals("await screen.getByTestId('email-input').fill('abc');", r.code())
        r.delete()
        assertEquals("await screen.getByTestId('email-input').fill('ab');", r.code())
        r.delete(); r.delete()
        assertEquals("await screen.getByTestId('email-input').tap();", r.code())
    }

    @Test
    fun `typing without a tap is kept as a comment`() {
        val r = recorder()
        r.type("hi")
        assertTrue(r.code().startsWith("// typed 'hi'"))
    }

    @Test
    fun `swipes use the finger direction`() {
        val r = recorder()
        r.swipe(100, 600, 100, 200)
        r.swipe(100, 200, 100, 600)
        r.swipe(300, 300, 50, 300)
        assertEquals("await screen.swipe('up');\nawait screen.swipe('down');\nawait screen.swipe('left');", r.code())
    }

    @Test
    fun `home and back buttons`() {
        val ios = recorder().apply { key(DeviceKey.HOME); key(DeviceKey.BACK) }
        assertEquals("await screen.pressButton('HOME');\n// pressed BACK", ios.code())
        val android = ScriptRecorder(Platform.ANDROID).apply { key(DeviceKey.BACK) }
        assertEquals("await screen.goBack();", android.code())
    }

    @Test
    fun `a tap with no element or no locator is a comment, never raw coordinates`() {
        val r = recorder()
        r.tap(ios, 5000, 5000)
        r.tapWithoutHierarchy(1, 2, "timeout")
        assertTrue(r.steps.all { it is ScriptRecorder.Note })
        assertTrue(r.code().lines().all { it.startsWith("//") })
    }

    @Test
    fun `duplicate locators get an index`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        repeat(3) { i ->
            root.addChild(UiNode(Platform.IOS, "XCUIElementTypeButton", mapOf("name" to "Add", "label" to "Add", "visible" to "true"), Rect(0, i * 100, 80, 40)))
        }
        val r = recorder()
        r.tap(root, 10, 215) // third button
        assertEquals("await screen.getByRole('button', { name: 'Add' }).nth(2).tap();", r.code())
    }

    @Test
    fun `a text inside a button resolves to the buttons test id`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        val button = UiNode(Platform.IOS, "XCUIElementTypeButton", mapOf("name" to "submit-btn", "label" to "Submit", "visible" to "true"), Rect(0, 0, 200, 50))
        root.addChild(button)
        button.addChild(UiNode(Platform.IOS, "XCUIElementTypeStaticText", mapOf("name" to "Submit", "label" to "Submit", "visible" to "true"), Rect(60, 10, 80, 30)))
        val r = recorder()
        r.tap(root, 100, 25)
        assertEquals("await screen.getByTestId('submit-btn').tap();", r.code())
    }

    @Test
    fun `a tap matched against an older screen is flagged`() {
        val r = recorder()
        r.tap(ios, 100, 220, verified = false)
        assertTrue(r.code().startsWith("await screen.getByTestId('email-input').tap();  // check"))
        r.type("a")
        assertTrue(r.code().contains(".fill('a');  // check"))
    }
}
