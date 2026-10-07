package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.HierarchyQueries
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorGenerator
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators
import io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    private fun android(): UiNode = UiAutomatorParser.parse(fixture("android_login.xml"))
    private fun ios(): UiNode = WdaSourceParser.parse(fixture("ios_login.xml"))

    @Test
    fun `role names follow the mobilewright role map`() {
        fun role(platform: Platform, rawType: String) = MobilewrightLocators.role(UiNode(platform, rawType, emptyMap(), null))
        assertEquals("text", role(Platform.IOS, "XCUIElementTypeStaticText"))
        assertEquals("listitem", role(Platform.IOS, "XCUIElementTypeCell"))
        assertEquals("listitem", role(Platform.IOS, "XCUIElementTypeOther"))
        assertEquals("button", role(Platform.ANDROID, "com.google.android.material.button.MaterialButton"))
        assertEquals("textfield", role(Platform.ANDROID, "androidx.appcompat.widget.AppCompatEditText"))
        assertEquals("radio", role(Platform.ANDROID, "android.widget.RadioButton"))
        assertEquals("switch", role(Platform.ANDROID, "android.widget.ToggleButton"))
        assertEquals(null, role(Platform.ANDROID, "android.view.View"))
    }

    @Test
    fun `parses uiautomator hierarchy`() {
        val root = android()
        val nodes = root.walk().toList()
        assertEquals(7, nodes.size)
        val email = nodes.first { it.resourceId == "com.example.app:id/email" }
        assertEquals("EditText", email.type)
        assertEquals("Email", email.label)
        assertEquals(Rect(40, 400, 1000, 120), email.bounds)
        assertEquals("/hierarchy/android.widget.FrameLayout[1]/android.widget.LinearLayout[1]/android.widget.EditText[1]", email.absoluteXPath())
        val secondButton = nodes.filter { it.type == "Button" }[1]
        assertEquals(2, secondButton.xpathIndex())
    }

    @Test
    fun `parses wda source`() {
        val root = ios()
        assertEquals("Application", root.type)
        val field = root.walk().first { it.type == "TextField" }
        assertEquals("email-input", field.identifier)
        assertEquals("Email", field.label)
        val button = root.walk().first { it.type == "Button" }
        // name equals label, so it is not treated as an accessibility identifier
        assertEquals(null, button.identifier)
    }

    @Test
    fun `deepest node at point skips invisible nodes`() {
        val root = ios()
        val hit = HierarchyQueries.deepestAt(root, 25, 305)
        assertEquals("Sign In", hit?.label)
        val androidHit = HierarchyQueries.deepestAt(android(), 500, 450)
        assertEquals("com.example.app:id/email", androidHit?.resourceId)
    }

    @Test
    fun `mobilewright priority matches the inspector`() {
        val root = android()
        val email = root.walk().first { it.resourceId != null && it.resourceId!!.endsWith("email") }
        val best = MobilewrightLocators.derive(email)!!
        assertEquals(MobilewrightLocators.Kind.TEST_ID, best.kind)
        assertEquals("screen.getByTestId('com.example.app:id/email')", MobilewrightLocators.toCode(best))

        val welcome = root.walk().first { it.text == "Welcome" }
        assertEquals("screen.getByRole('text', { name: 'Welcome' })", MobilewrightLocators.toCode(MobilewrightLocators.derive(welcome)!!))

        val iosField = ios().walk().first { it.type == "TextField" }
        assertEquals("screen.getByTestId('email-input')", MobilewrightLocators.toCode(MobilewrightLocators.derive(iosField)!!))
    }

    @Test
    fun `duplicate locators are detected`() {
        val root = android()
        val button = root.walk().first { it.type == "Button" }
        val suggestions = LocatorGenerator.suggest(button, root)
        val best = suggestions.first { it.isBest }
        assertEquals("screen.getByRole('button', { name: 'Sign In' })", best.expression)
        assertEquals(2, best.matchCount)
        assertFalse(best.isUnique)
        val absolute = suggestions.first { it.strategy == "xpath (absolute)" }
        assertTrue(absolute.isUnique)
    }

    @Test
    fun `ios appium locators`() {
        val root = ios()
        val field = root.walk().first { it.type == "TextField" }
        val suggestions = LocatorGenerator.suggest(field, root)
        val predicate = suggestions.first { it.strategy == "-ios predicate string" }
        assertEquals("type == 'XCUIElementTypeTextField' AND name == 'email-input'", predicate.expression)
        assertTrue(predicate.isUnique)
        val chain = suggestions.first { it.strategy == "-ios class chain" }
        assertEquals("**/XCUIElementTypeTextField[`name == 'email-input'`]", chain.expression)
        assertNotNull(suggestions.firstOrNull { it.strategy == "accessibility id" })
    }

    @Test
    fun `xpath literal handles quotes`() {
        assertEquals("\"a\"", LocatorGenerator.xpathLiteral("a"))
        assertEquals("'say \"hi\"'", LocatorGenerator.xpathLiteral("say \"hi\""))
        assertEquals("concat(\"it's \", '\"', \"x\", '\"', \"\")", LocatorGenerator.xpathLiteral("it's \"x\""))
    }
}
