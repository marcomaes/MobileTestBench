package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorGenerator
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorQuery
import io.github.marcomaes.mobiletestbench.core.inspector.ScopedLocators
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.recording.ScriptRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A product list: every card has the same "add-to-cart" button, only the cards have their own test id. */
class ScopedLocatorsTest {

    private fun el(type: String, name: String?, label: String?, rect: Rect): UiNode = UiNode(
        Platform.IOS, "XCUIElementType$type",
        buildMap { name?.let { put("name", it) }; label?.let { put("label", it) }; put("visible", "true") },
        rect,
    )

    private val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
    private val cards = (1..3).map { i ->
        el("Other", "product-item-$i", "Product $i", Rect(0, (i - 1) * 100, 400, 100)).also(root::addChild)
    }
    private val buttons = cards.mapIndexed { i, card ->
        el("Button", "add-to-cart", "Add to cart", Rect(300, i * 100 + 50, 80, 40)).also(card::addChild)
    }

    @Test
    fun `a duplicate test id is scoped by the card it is in`() {
        val scoped = ScopedLocators.forNode(buttons[1], root)!!
        assertEquals("screen.getByTestId('product-item-2').getByTestId('add-to-cart')", scoped.code())
        assertSame(cards[1], scoped.parentNode)
    }

    @Test
    fun `the suggestions put the scoped locator first and keep the plain one`() {
        val suggestions = LocatorGenerator.suggest(buttons[1], root)
        val first = suggestions.first()
        assertEquals("test id in parent", first.strategy)
        assertEquals("screen.getByTestId('product-item-2').getByTestId('add-to-cart')", first.expression)
        assertTrue(first.isBest && first.isUnique)
        assertNull(first.hint)
        val plain = suggestions.first { it.expression == "screen.getByTestId('add-to-cart')" }
        assertFalse(plain.isBest)
        assertEquals(3, plain.matchCount)
        val xpath = suggestions.first { it.strategy == "xpath (in parent)" }
        assertEquals("//XCUIElementTypeOther[@name=\"product-item-2\"]//XCUIElementTypeButton[@name=\"add-to-cart\"]", xpath.expression)
        assertSame(buttons[1], LocatorQuery.find(xpath.expression, root).single())
    }

    @Test
    fun `a locator that is already unique gets no scoped variant`() {
        val suggestions = LocatorGenerator.suggest(cards[0], root)
        assertTrue(suggestions.none { it.strategy.endsWith("in parent") })
        assertTrue(suggestions.first().isBest)
    }

    @Test
    fun `chained locators are evaluated against the parent only`() {
        val found = LocatorQuery.find("screen.getByTestId('product-item-3').getByTestId('add-to-cart')", root)
        assertSame(buttons[2], found.single())
        assertEquals(3, LocatorQuery.find("screen.getByTestId('add-to-cart')", root).size)
        assertSame(buttons[0], LocatorQuery.find("screen.getByTestId('add-to-cart').first()", root).single())
        assertSame(buttons[2], LocatorQuery.find("getByTestId('add-to-cart').nth(2)", root).single())
        assertSame(buttons[2], LocatorQuery.find("screen.getByTestId('add-to-cart').last()", root).single())
        assertTrue(LocatorQuery.find("screen.getByTestId('nope').getByTestId('add-to-cart')", root).isEmpty())
    }

    @Test
    fun `bad chains are rejected with a readable message`() {
        assertTrue(runCatching { LocatorQuery.find("screen.getByTestId('a').tap()", root) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LocatorQuery.find("screen.getByTestId('a'", root) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LocatorQuery.find("screen.getByTestId('a').nth(x)", root) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `parentheses and quotes inside a value do not confuse the parser`() {
        val odd = el("Button", "x", "Say (hi), 'you'", Rect(0, 500, 50, 20)).also(root::addChild)
        assertSame(odd, LocatorQuery.find("screen.getByLabel('Say (hi), \\'you\\'')", root).single())
    }

    @Test
    fun `the recorder uses the parent instead of an index and replay finds the same element`() {
        val recorder = ScriptRecorder(Platform.IOS)
        recorder.tap(root, 340, 170) // second button
        assertEquals("await screen.getByTestId('product-item-2').getByTestId('add-to-cart').tap();", recorder.code())
        val target = (recorder.steps.single() as ScriptRecorder.Tap).target
        assertNotNull(target.parent)
        assertNull(target.nth)
        assertSame(buttons[1], target.resolve(root))
        // The element is gone: nothing to find.
        assertNull(target.resolve(UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)))
    }

    @Test
    fun `without any helpful parent the recorder still falls back to an index`() {
        val flat = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        repeat(2) { i -> flat.addChild(el("Button", "ok", "OK", Rect(0, i * 60, 80, 40))) }
        val recorder = ScriptRecorder(Platform.IOS)
        recorder.tap(flat, 10, 70)
        assertEquals("await screen.getByTestId('ok').nth(1).tap();", recorder.code())
    }

    @Test
    fun `every suggested locator finds its own element`() {
        for (node in buttons + cards) {
            LocatorGenerator.suggest(node, root)
                .filter { it.framework == "Mobilewright" || it.strategy.startsWith("xpath") }
                .forEach { s ->
                    val found = LocatorQuery.find(s.expression, root)
                    assertTrue("${s.expression} should find the element", found.any { it === node })
                    assertEquals(s.expression, s.matchCount, found.size)
                }
        }
    }

    @Test
    fun `a card that is a sibling of its button in a flattened tree is still a parent`() {
        val flat = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)
        val list = UiNode(Platform.ANDROID, "android.widget.ScrollView", mapOf("resource-id" to "product-list"), Rect(0, 0, 1000, 1000)).also(flat::addChild)
        val buttons = (1..3).map { i ->
            // The card is an empty sibling; its button comes later in the same list and lies inside its bounds.
            list.addChild(UiNode(Platform.ANDROID, "android.view.ViewGroup", mapOf("resource-id" to "product-item-$i"), Rect(40, (i - 1) * 300, 920, 280)))
            UiNode(Platform.ANDROID, "android.view.ViewGroup",
                mapOf("resource-id" to "add-to-cart-button", "content-desc" to "Add product to cart"), Rect(400, (i - 1) * 300 + 150, 400, 100))
                .also(list::addChild)
        }
        val scoped = ScopedLocators.forNode(buttons[1], flat)!!
        assertEquals("screen.getByTestId('product-item-2').getByTestId('add-to-cart-button')", scoped.code())
        assertSame(buttons[1], LocatorQuery.find(scoped.code(), flat).single())
        val suggestions = LocatorGenerator.suggest(buttons[1], flat)
        assertEquals("test id in parent", suggestions.first().strategy)
        // The card is a preceding sibling of its button: the XPath names the card and walks to the next matching sibling.
        val xpath = suggestions.first { it.strategy == "xpath (in parent)" }
        assertEquals(
            "//android.view.ViewGroup[@resource-id=\"product-item-2\"]/following-sibling::android.view.ViewGroup[@resource-id=\"add-to-cart-button\"][1]",
            xpath.expression,
        )
        assertSame(buttons[1], LocatorQuery.find(xpath.expression, flat).single())
        assertEquals(1, xpath.matchCount)
        val recorder = ScriptRecorder(Platform.ANDROID)
        recorder.tap(flat, 600, 500)
        assertEquals("await screen.getByTestId('product-item-2').getByTestId('add-to-cart-button').tap();", recorder.code())
    }

    @Test
    fun `a tap on the text inside a duplicate button records the buttons scoped test id`() {
        val flat = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)
        val list = UiNode(Platform.ANDROID, "android.widget.ScrollView", mapOf("resource-id" to "product-list"), Rect(0, 0, 1000, 1000)).also(flat::addChild)
        (1..2).forEach { i ->
            list.addChild(UiNode(Platform.ANDROID, "android.view.ViewGroup", mapOf("resource-id" to "product-item-$i"), Rect(40, (i - 1) * 400, 920, 380)))
            val button = UiNode(Platform.ANDROID, "android.view.ViewGroup",
                mapOf("resource-id" to "add-to-cart-button", "content-desc" to "Add product to cart", "clickable" to "true"), Rect(400, (i - 1) * 400 + 200, 400, 100))
            list.addChild(button)
            button.addChild(UiNode(Platform.ANDROID, "android.widget.TextView", mapOf("text" to "Add to Cart"), Rect(500, (i - 1) * 400 + 230, 200, 40)))
        }
        val recorder = ScriptRecorder(Platform.ANDROID)
        recorder.tap(flat, 600, 650) // the text inside the second button
        assertEquals("await screen.getByTestId('product-item-2').getByTestId('add-to-cart-button').tap();", recorder.code())
    }

    @Test
    fun `when the parent comes after the element a preceding axis is used`() {
        val screen = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        val list = el("Other", "list", null, Rect(0, 0, 400, 600)).also(screen::addChild)
        val buttons = (0..1).map { i ->
            val b = el("Button", "buy", "Buy", Rect(300, i * 200 + 120, 80, 40)).also(list::addChild)
            list.addChild(el("Other", "row-$i", "Row $i", Rect(0, i * 200, 400, 200))) // the row comes after its button
            b
        }
        val xpath = LocatorGenerator.suggest(buttons[1], screen).first { it.strategy == "xpath (in parent)" }
        assertEquals(
            "//XCUIElementTypeOther[@name=\"row-1\"]/preceding-sibling::XCUIElementTypeButton[@name=\"buy\"][1]",
            xpath.expression,
        )
        assertSame(buttons[1], LocatorQuery.find(xpath.expression, screen).single())
    }
}
