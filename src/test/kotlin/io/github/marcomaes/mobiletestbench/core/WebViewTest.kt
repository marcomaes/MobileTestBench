package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.webview.DomNode
import io.github.marcomaes.mobiletestbench.core.webview.DomTree
import io.github.marcomaes.mobiletestbench.core.webview.LiveCheck
import io.github.marcomaes.mobiletestbench.core.webview.WebLocators
import io.github.marcomaes.mobiletestbench.core.webview.WebRectMapper
import io.github.marcomaes.mobiletestbench.core.webview.WebViewEval
import io.github.marcomaes.mobiletestbench.core.webview.WebViews
import io.github.marcomaes.mobiletestbench.core.webview.matchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewTest {

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private val androidPage by lazy { DomTree.parse(fixture("mobilecli-webview-tree-android.json")) }
    private val iosPage by lazy { DomTree.parse(fixture("mobilecli-webview-tree-ios.json")) }

    private fun find(page: io.github.marcomaes.mobiletestbench.core.webview.DomPage, pred: (DomNode) -> Boolean) = page.root.descendants().first(pred)

    // list

    @Test
    fun `list parses android and ios`() {
        val a = WebViews.parseList(fixture("mobilecli-webview-list-android.json")).single()
        assertEquals("b11ce8a", a.id)
        assertEquals("Sample Login", a.title)
        assertEquals("com.mobilenext.playground", a.bundleId)
        assertEquals(Rect(0, 289, 1080, 2135), a.bounds)
        assertTrue(a.visible)
        assertEquals("Sample Login  -  mobilewright.dev/samples/webview/  -  com.mobilenext.playground", a.label)
        val i = WebViews.parseList(fixture("mobilecli-webview-list-ios.json")).single()
        assertEquals("0x105eac000", i.id)
        assertEquals("", i.bundleId)
        assertEquals(Rect(0, 116, 402, 758), i.bounds)
    }

    @Test
    fun `empty list and no data`() {
        assertEquals(emptyList<Any>(), WebViews.parseList("""{"status":"ok","data":[]}"""))
        assertEquals(emptyList<Any>(), WebViews.parseList("""{"status":"ok"}"""))
    }

    @Test
    fun `list error is thrown with mobilecli's text`() {
        val e = runCatching { WebViews.parseList(fixture("mobilecli-webview-list-error-android.json")) }.exceptionOrNull()
        assertTrue(e is MobilecliException)
        assertTrue(e!!.message!!.contains("not debuggable"))
    }

    // errors

    @Test
    fun `clean error strips agent prefixes`() {
        assertEquals("foo is not defined", WebViews.cleanError("webview evaluate failed: agent error -32603: Internal error: foo is not defined"))
        assertEquals("ReferenceError: Can't find variable: foo", WebViews.cleanError("webview evaluate failed: agent error -32000: ReferenceError: Can't find variable: foo"))
        assertEquals("JavaScript execution returned a result of an unsupported type", WebViews.cleanError(MobilecliJson.errorMessage(fixture("mobilecli-webview-eval-unsupported-ios.json"), "")))
        assertEquals("Error: boom", WebViews.cleanError(MobilecliJson.errorMessage(fixture("mobilecli-webview-eval-throw-ios.json"), "")))
    }

    @Test
    fun `debug build hint`() {
        val android = MobilecliJson.errorMessage(fixture("mobilecli-webview-list-error-android.json"), "")
        val ios = MobilecliJson.errorMessage(fixture("mobilecli-webview-list-error-ios.json"), "")
        assertTrue(WebViews.needsDebugBuild(android))
        assertTrue(WebViews.needsDebugBuild(ios))
        assertTrue(WebViews.describeError(android).contains("debug builds"))
        assertTrue(WebViews.describeError(ios).contains("get-task-allow"))
        assertFalse(WebViews.needsDebugBuild("foo is not defined"))
        assertFalse(WebViews.describeError("foo is not defined").contains("debug"))
    }

    @Test
    fun `not found detection`() {
        assertTrue(WebViews.isNotFound(MobilecliJson.errorMessage(fixture("mobilecli-webview-eval-notfound-android.json"), "")))
        assertTrue(WebViews.isNotFound(MobilecliJson.errorMessage(fixture("mobilecli-webview-eval-notfound-ios.json"), "")))
        assertFalse(WebViews.isNotFound("foo is not defined"))
    }

    // eval

    @Test
    fun `expression wraps the script as an indirect eval of a json string`() {
        assertEquals("""(0,eval)("1+1 // c")""", WebViewEval.expression("1+1 // c"))
        assertEquals("""(0,eval)("-1")""", WebViewEval.expression("-1"))
        assertEquals("""(0,eval)("a\nb \"q\" <x>")""", WebViewEval.expression("a\nb \"q\" <x>"))
        assertEquals(listOf("webview", "eval", "abc", """(0,eval)("2")"""), WebViewEval.args("abc", "2"))
        assertTrue(WebViewEval.expression("-1").startsWith("("))
    }

    @Test
    fun `format of results`() {
        assertEquals(EvalOutcomeOf("2", false), WebViewEval.format("""{"status":"ok","data":2}""").let { EvalOutcomeOf(it.text, it.error) })
        val obj = WebViewEval.format(fixture("mobilecli-webview-eval-object-android.json"))
        assertFalse(obj.error)
        assertTrue(obj.text.contains("\"a\": 1"))
        val undefined = WebViewEval.format(fixture("mobilecli-webview-eval-undefined.json"))
        assertFalse(undefined.error)
        assertTrue(undefined.text.startsWith("undefined"))
        assertEquals("\"hi <b>\"", WebViewEval.format("""{"status":"ok","data":"hi <b>"}""").text)
    }

    @Test
    fun `format of errors`() {
        val e = WebViewEval.format(fixture("mobilecli-webview-eval-error-android.json"))
        assertTrue(e.error)
        assertEquals("foo is not defined", e.text)
        val u = WebViewEval.formatError(MobilecliJson.errorMessage(fixture("mobilecli-webview-eval-unsupported-ios.json"), ""))
        assertTrue(u.error)
        assertTrue(u.text.contains("JSON.stringify"))
        assertFalse(u.text.contains("body["))
    }

    private data class EvalOutcomeOf(val text: String, val error: Boolean)

    // tree

    @Test
    fun `parses the android tree`() {
        val p = androidPage
        assertEquals(2.625, p.dpr, 0.0001)
        assertEquals(411.0, p.innerWidth, 0.0)
        assertEquals(17, p.count)
        assertFalse(p.truncated)
        assertEquals("html", p.root.tag)
        val input = find(p) { it.id == "name" }
        assertEquals("input", input.tag)
        assertEquals("input#name", input.label)
        assertEquals(listOf(1, 0, 1, 0, 1), input.path)
        assertEquals("Submit", find(p) { it.tag == "button" }.text)
        assertEquals("div.container", find(p) { it.classes.contains("container") }.label)
        assertTrue(find(p) { it.tag == "script" }.isNonVisual)
        assertEquals(p.count, p.root.descendants().count())
    }

    @Test
    fun `parses the ios tree and accepts a plain object as data`() {
        assertEquals(402.0, iosPage.innerWidth, 0.0)
        assertEquals(3.0, iosPage.dpr, 0.0)
        val plain = DomTree.parse("""{"status":"ok","data":{"iw":10,"root":{"t":"html","a":{},"x":"","r":[0,0,10,10],"k":[]}}}""")
        assertEquals(1, plain.count)
    }

    @Test
    fun `truncation uses total or a full cap`() {
        val capped = DomTree.parse("""{"status":"ok","data":{"iw":10,"count":2000,"total":14123,"root":{"t":"html","a":{},"x":"","r":[0,0,1,1],"k":[]}}}""")
        assertTrue(capped.truncated)
        assertEquals("showing the first 2000 of 14123 elements", capped.note)
        val full = DomTree.parse("""{"status":"ok","data":{"iw":10,"count":2000,"root":{"t":"html","a":{},"x":"","r":[0,0,1,1],"k":[]}}}""")
        assertTrue(full.truncated)
        assertEquals("17 elements", androidPage.note)
    }

    @Test
    fun `tree failures`() {
        assertTrue(runCatching { DomTree.parse("""{"status":"ok"}""") }.isFailure)
        assertTrue(runCatching { DomTree.parse("""{"status":"error","error":"x"}""") }.exceptionOrNull() is MobilecliException)
    }

    @Test
    fun `tree script is the verified fixture plus the cap and the real total`() {
        val fixtureScript = fixture("webview-tree-script.js").trim()
        val s = DomTree.script(2000)
        assertTrue(s.startsWith("JSON.stringify("))
        assertTrue(s.contains("MAX=2000"))
        assertTrue(s.contains("total:document.getElementsByTagName('*').length"))
        // The walker itself is unchanged from the script verified on the devices.
        val walker = fixtureScript.substringAfter("(function(){").substringBefore("return {dpr")
        assertTrue(s.contains(walker))
        assertTrue(DomTree.script(50).contains("MAX=50"))
        assertTrue(WebViewEval.expression(s).startsWith("(0,eval)(\""))
    }

    // locators

    @Test
    fun `css prefers a unique id`() {
        val input = find(androidPage) { it.id == "name" }
        val css = WebLocators.cssSelector(input, androidPage.root)
        assertEquals("#name", css.value)
        assertEquals(1, css.domCount)
        val xp = WebLocators.xpath(input, androidPage.root)
        assertEquals("//input[@id='name']", xp.value)
        assertEquals(1, xp.domCount)
    }

    @Test
    fun `css without id uses a unique class or falls back to a path`() {
        val container = find(androidPage) { it.classes.contains("container") }
        assertEquals("div.container", WebLocators.cssSelector(container, androidPage.root).value)
        val button = find(androidPage) { it.tag == "button" }
        val css = WebLocators.cssSelector(button, androidPage.root)
        assertEquals(1, css.domCount)
        assertEquals("#loginForm > button", css.value)
        val xp = WebLocators.xpath(button, androidPage.root)
        assertEquals("//*[@id='loginForm']/button", xp.value)
        val h1 = find(androidPage) { it.tag == "h1" }
        assertEquals("div.container > h1", WebLocators.cssSelector(h1, androidPage.root).value)
        assertEquals("/html/body/div[1]/h1", WebLocators.xpath(h1, androidPage.root).value)
    }

    private fun page(json: String) = DomTree.parse("""{"status":"ok","data":$json}""")

    private val list = page(
        """{"iw":100,"root":{"t":"html","a":{},"x":"","r":[0,0,1,1],"k":[{"t":"body","a":{},"x":"","r":[0,0,1,1],"k":[
        {"t":"ul","a":{"id":"menu"},"x":"","r":[0,0,1,1],"k":[
          {"t":"li","a":{"class":"item"},"x":"A","r":[0,0,1,1],"k":[]},
          {"t":"li","a":{"class":"item"},"x":"B","r":[0,0,1,1],"k":[]},
          {"t":"li","a":{"class":"item","data-testid":"third"},"x":"C","r":[0,0,1,1],"k":[]}]},
        {"t":"ul","a":{},"x":"","r":[0,0,1,1],"k":[
          {"t":"li","a":{"class":"item"},"x":"D","r":[0,0,1,1],"k":[]}]}]}]}}""",
    )

    @Test
    fun `duplicates are disambiguated and counted`() {
        val items = list.root.descendants().filter { it.tag == "li" }.toList()
        val second = items[1]
        val css = WebLocators.cssSelector(second, list.root)
        assertEquals("#menu > li:nth-of-type(2)", css.value)
        assertEquals(1, css.domCount)
        assertEquals("//*[@id='menu']/li[2]", WebLocators.xpath(second, list.root).value)
        // a test id wins over the path
        assertEquals("li[data-testid=\"third\"]", WebLocators.cssSelector(items[2], list.root).value)
        assertEquals("//li[@data-testid='third']", WebLocators.xpath(items[2], list.root).value)
        // an element in the second list (no id) needs the ancestor path
        val d = items[3]
        val dc = WebLocators.cssSelector(d, list.root)
        assertEquals(1, dc.domCount)
        assertEquals("ul:nth-of-type(2) > li", dc.value)
        assertEquals("/html/body/ul[2]/li", WebLocators.xpath(d, list.root).value)
    }

    @Test
    fun `quoting`() {
        assertEquals("\"a\\\"b\"", WebLocators.quote("a\"b"))
        assertEquals("'abc'", WebLocators.xpathLiteral("abc"))
        assertEquals("\"it's\"", WebLocators.xpathLiteral("it's"))
        assertEquals("concat('a', \"'\", 'b\"c')", WebLocators.xpathLiteral("a'b\"c"))
    }

    @Test
    fun `an id that is not a css identifier uses an attribute selector in paths`() {
        val p = page("""{"iw":1,"root":{"t":"html","a":{},"x":"","r":[0,0,1,1],"k":[{"t":"body","a":{},"x":"","r":[0,0,1,1],"k":[
            {"t":"div","a":{"id":"1 a"},"x":"","r":[0,0,1,1],"k":[{"t":"p","a":{},"x":"","r":[0,0,1,1],"k":[]}]}]}]}}""")
        val pNode = find(p) { it.tag == "p" }
        assertEquals("[id=\"1 a\"] > p", WebLocators.cssSelector(pNode, p.root).value)
    }

    // live check

    @Test
    fun `live check script embeds selectors as string literals and the path`() {
        val s = LiveCheck.script("input[name=\"x\"]", "//a[@id='b']", listOf(1, 0, 2))
        assertTrue(s.contains("querySelectorAll(\"input[name=\\\"x\\\"]\")"))
        assertTrue(s.contains("document.evaluate(\"//a[@id='b']\""))
        assertTrue(s.contains("var p=[1,0,2]"))
        assertTrue(s.endsWith("JSON.stringify(o)})()"))
    }

    @Test
    fun `live check parse`() {
        val out = """{"status":"ok","data":"{\"css\":1,\"xpath\":2,\"rect\":[25.7,386.7,360,50.5],\"vw\":411.4}"}"""
        val c = LiveCheck.parse(out)
        assertEquals(1, c.cssCount)
        assertEquals(2, c.xpathCount)
        assertNull(c.cssError)
        assertEquals(411.4, c.viewportWidth, 0.0)
        assertEquals(50.5, c.rect!![3], 0.0)
        val bad = LiveCheck.parse("""{"status":"ok","data":"{\"cssError\":\"Failed to execute 'querySelectorAll' on 'Document': 'div[[' is not a valid selector.\",\"xpath\":0,\"iw\":402}"}""")
        assertNull(bad.cssCount)
        assertNotNull(bad.cssError)
        assertEquals("invalid: ${bad.cssError}", matchText(bad.cssCount, bad.cssError))
        assertEquals("no match", matchText(bad.xpathCount))
    }

    @Test
    fun `match text`() {
        assertEquals("1 match (unique)", matchText(1))
        assertEquals("3 matches", matchText(3))
        assertEquals("not checked", matchText(null))
    }

    // rect mapping, verified numbers from the devices

    @Test
    fun `maps a css rect to device coordinates`() {
        val android = WebRectMapper.toDevice(doubleArrayOf(25.714, 386.667, 360.0, 50.476), 411.4, Rect(0, 289, 1080, 2135))!!
        assertEquals(Rect(68, 1304, 945, 133), android) // device x 67.5, y 1304.0, w 945, h 132.5 measured on the emulator
        val ios = WebRectMapper.toDevice(doubleArrayOf(24.0, 342.0, 354.0, 52.0), 402.0, Rect(0, 116, 402, 758))!!
        assertEquals(Rect(24, 458, 354, 52), ios)
        assertNull(WebRectMapper.toDevice(doubleArrayOf(0.0, 0.0, 0.0, 0.0), 402.0, Rect(0, 116, 402, 758)))
        assertNull(WebRectMapper.toDevice(doubleArrayOf(1.0, 1.0, 5.0, 5.0), 0.0, Rect(0, 116, 402, 758)))
    }

    // native owner

    @Test
    fun `native owner is found by rect`() {
        val root = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), Rect(0, 0, 1080, 2424))
        val frame = UiNode(Platform.ANDROID, "android.widget.FrameLayout", emptyMap(), Rect(0, 289, 1080, 2135))
        val wv = UiNode(Platform.ANDROID, "android.webkit.WebView", emptyMap(), Rect(0, 289, 1080, 2135))
        root.addChild(frame)
        frame.addChild(wv)
        val owner = WebViews.nativeOwner(root, Rect(0, 289, 1080, 2135))
        assertEquals(wv, owner)
        assertEquals("android.webkit.WebView at 0,289 1080x2135", WebViews.describeOwner(wv))
        assertNull(WebViews.nativeOwner(root, Rect(0, 0, 10, 10)))
        // without a WebView node (iOS) no owner is claimed: only the bounds are known
        val ios = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), Rect(0, 0, 402, 874))
        val other = UiNode(Platform.IOS, "XCUIElementTypeOther", emptyMap(), Rect(0, 116, 402, 758))
        ios.addChild(other)
        assertNull(WebViews.nativeOwner(ios, Rect(0, 116, 402, 758)))
    }

    @Test
    fun `rect is clipped to the web view bounds`() {
        val b = Rect(0, 100, 400, 600)
        assertEquals(Rect(10, 650, 100, 50), WebRectMapper.clip(Rect(10, 650, 100, 200), b))
        assertNull(WebRectMapper.clip(Rect(10, 900, 100, 50), b))
        assertNull(WebRectMapper.clip(Rect(10, 20, 100, 50), b))
    }

    @Test
    fun `xpath uses local-name for svg elements`() {
        val html = DomNode("html", emptyMap(), "", doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), null)
        val svg = DomNode("svg", mapOf("id" to "x"), "", doubleArrayOf(0.0, 0.0, 1.0, 1.0), listOf(0), html)
        val path = DomNode("path", emptyMap(), "", doubleArrayOf(0.0, 0.0, 1.0, 1.0), listOf(0, 0), svg)
        html.add(svg)
        svg.add(path)
        assertEquals("//*[local-name()='svg'][@id='x']", WebLocators.xpath(svg, html).value)
        assertEquals("//*[@id='x']/*[local-name()='path']", WebLocators.xpath(path, html).value)
    }
}
