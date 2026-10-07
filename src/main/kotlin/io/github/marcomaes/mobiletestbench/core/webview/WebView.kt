package io.github.marcomaes.mobiletestbench.core.webview

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.MobilecliJson
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import kotlin.math.roundToInt

/** A web view of the foreground app as `mobilecli webview list` reports it. [bounds] are device pixels (Android) or points (iOS). */
data class WebViewInfo(val id: String, val url: String, val title: String, val bundleId: String, val bounds: Rect?, val visible: Boolean) {
    /** One line for the combo box. */
    val label: String
        get() {
            val name = title.ifBlank { url.ifBlank { id } }
            val shortUrl = url.removePrefix("https://").removePrefix("http://").substringBefore('?').takeIf { it.isNotBlank() && it != name }
            return listOfNotNull(name, shortUrl, bundleId.takeIf { it.isNotBlank() }).joinToString("  -  ") + if (visible) "" else "  (hidden)"
        }
}

/** Pure parsing of `mobilecli webview` output and the messages shown for its errors. */
object WebViews {

    fun parseList(stdout: String): List<WebViewInfo> {
        val data = MobilecliJson.data(stdout)
        if (data == null || !data.isJsonArray) return emptyList()
        return data.asJsonArray.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val b = o.get("bounds")?.takeIf { it.isJsonObject }?.asJsonObject
            val bounds = b?.let { Rect(it.int("x"), it.int("y"), it.int("width"), it.int("height")) }
            WebViewInfo(id, o.str("url").orEmpty(), o.str("title").orEmpty(), o.str("bundleId").orEmpty(), bounds, o.get("isVisible")?.asBoolean ?: true)
        }
    }

    /**
     * mobilecli's error text without the agent noise: `webview evaluate failed: agent error -32603: Internal error: foo is not defined` becomes
     * `foo is not defined`, and the iOS debug tail ` || body[0:120]=...` is cut.
     */
    fun cleanError(message: String): String {
        var m = message.substringBefore(" || body[").trim()
        m = m.replace(Regex("^webview (evaluate|list|query) failed: "), "")
        m = m.replace(Regex("^agent error -?\\d+: "), "")
        m = m.removePrefix("Internal error: ")
        return m.ifBlank { "webview command failed" }
    }

    /** True for the "not a debug build" family of errors (Android: not debuggable, iOS: no get-task-allow). */
    fun needsDebugBuild(message: String): Boolean =
        message.contains("debug build", true) || message.contains("not debuggable", true) || message.contains("get-task-allow", true)

    /** The error shown in the status line: the cleaned text plus a hint when the app is not a debug build. */
    fun describeError(message: String): String {
        val clean = cleanError(message)
        return if (needsDebugBuild(message)) "$clean. Web views can only be inspected in debug builds (Android: debuggable app, iOS: get-task-allow entitlement)." else clean
    }

    /** True when the web view is gone (its id is stale, for example after leaving and re-entering the screen). */
    fun isNotFound(message: String): Boolean = message.contains("webview not found", true) || message.contains("-32100")

    /** The native element that owns the web view: a node whose type contains "WebView" (Android `android.webkit.WebView`) with the same rect. iOS has no such node, so there only the bounds are known. */
    fun nativeOwner(root: UiNode, bounds: Rect, tolerance: Int = 2): UiNode? {
        fun close(a: Rect) = kotlin.math.abs(a.x - bounds.x) <= tolerance && kotlin.math.abs(a.y - bounds.y) <= tolerance &&
            kotlin.math.abs(a.width - bounds.width) <= tolerance && kotlin.math.abs(a.height - bounds.height) <= tolerance
        val matches = mutableListOf<UiNode>()
        fun walk(n: UiNode) {
            if (n.bounds?.let(::close) == true) matches += n
            n.children.forEach(::walk)
        }
        walk(root)
        return matches.firstOrNull { it.rawType.contains("WebView", true) }
    }

    /** `Android: android.webkit.WebView at 0,289 1080x2135` style text for the owner. */
    fun describeOwner(node: UiNode): String {
        val b = node.bounds
        return "${node.rawType}" + (b?.let { " at ${it.x},${it.y} ${it.width}x${it.height}" } ?: "")
    }

    internal fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    internal fun JsonObject.int(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asDouble?.roundToInt() ?: 0
}

/** Result of a console evaluation: [text] is the pretty JSON (or "undefined"), [error] when the page or the web view failed. */
data class EvalOutcome(val text: String, val error: Boolean)

/** Building and reading `mobilecli webview eval` calls. */
object WebViewEval {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val pretty: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private const val MAX_RESULT_CHARS = 200_000

    /**
     * The argument for `webview eval`. mobilecli wraps the expression as `return (EXPR)`, which breaks statements, comments and `throw`,
     * and cobra reads a leading `-` as a flag. An indirect eval of a JSON string literal avoids both and returns the completion value.
     */
    fun expression(script: String): String = "(0,eval)(${gson.toJson(script)})"

    /** The arguments after `mobilecli`: `webview eval <id> <expr>`. */
    fun args(webViewId: String, script: String): List<String> = listOf("webview", "eval", webViewId, expression(script))

    /** Reads a successful eval envelope: an envelope without `data` is JavaScript undefined or null. Throws on an error envelope. */
    fun result(stdout: String): JsonElement? = MobilecliJson.data(stdout)

    /** The console view of [stdout] (success) as an [EvalOutcome]. */
    fun format(stdout: String): EvalOutcome {
        if (MobilecliJson.isError(stdout)) return EvalOutcome(WebViews.cleanError(MobilecliJson.errorMessage(stdout, "")), true)
        val data = result(stdout) ?: return EvalOutcome("undefined (or null, mobilecli reports both as no value)", false)
        val text = if (data.isJsonPrimitive && data.asJsonPrimitive.isString) gson.toJson(data.asString) else pretty.toJson(data)
        return EvalOutcome(if (text.length > MAX_RESULT_CHARS) text.take(MAX_RESULT_CHARS) + "\n... cut at $MAX_RESULT_CHARS characters" else text, false)
    }

    /** The console view of a failure message thrown by the mobilecli call, with a hint for the results mobilecli cannot convert. */
    fun formatError(message: String): EvalOutcome {
        val clean = WebViews.cleanError(message)
        val hint = if (clean.contains("unsupported type", true)) "\nReturn a primitive or JSON, for example JSON.stringify(value)." else ""
        return EvalOutcome(clean + hint, true)
    }
}

/** One element of the page as the tree script reports it. [path] is the index among the element children of each parent, from `<html>` down. */
class DomNode(
    val tag: String,
    val attributes: Map<String, String>,
    val text: String,
    /** Viewport relative rect in CSS pixels: x, y, width, height. */
    val rect: DoubleArray,
    val path: List<Int>,
    val parent: DomNode?,
) {
    private val _children = mutableListOf<DomNode>()
    val children: List<DomNode> get() = _children
    internal fun add(child: DomNode) { _children += child }

    val id: String? get() = attributes["id"]?.takeIf { it.isNotBlank() }
    val classes: List<String> get() = attributes["class"]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()

    /** `tag#id.class1.class2` as shown in the tree. */
    val label: String
        get() = tag + (id?.let { "#$it" } ?: "") + classes.joinToString("") { ".$it" }

    val isNonVisual: Boolean get() = tag in NON_VISUAL

    fun descendants(): Sequence<DomNode> = sequence { yield(this@DomNode); children.forEach { yieldAll(it.descendants()) } }

    companion object {
        val NON_VISUAL = setOf("head", "script", "style", "meta", "link", "title", "noscript", "template", "base")
    }
}

/** A parsed page: the root and the viewport facts needed to map rects to the device. */
class DomPage(val root: DomNode, val dpr: Double, val innerWidth: Double, val innerHeight: Double, val count: Int, val total: Int?, val limit: Int) {
    /** The CSS width the rects are relative to: the `<html>` width (fractional, exact), else the rounded `innerWidth`. */
    val viewportWidth: Double get() = root.rect[2].takeIf { it > 0 } ?: innerWidth

    /** True when the page has more elements than were loaded. With an unknown total (older script), a full cap counts as truncated. */
    val truncated: Boolean get() = if (total != null) total > count else count >= limit

    val note: String get() = if (truncated) "showing the first $count of ${total ?: "more than $count"} elements" else "$count elements"
}

object DomTree {
    const val DEFAULT_LIMIT = 2000

    /**
     * Parses the result of the tree script: [stdout] is the eval envelope whose `data` is a JSON string (the script returns
     * `JSON.stringify(...)`), but a plain object `data` is accepted too.
     */
    fun parse(stdout: String, limit: Int = DEFAULT_LIMIT): DomPage {
        val data = MobilecliJson.data(stdout) ?: error("The web view returned nothing")
        val obj = (if (data.isJsonPrimitive) JsonParser.parseString(data.asString) else data).asJsonObject
        val root = node(obj.getAsJsonObject("root") ?: error("The DOM has no root element"), null, emptyList())
        val count = obj.get("count")?.asInt ?: root.descendants().count()
        return DomPage(
            root, obj.get("dpr")?.asDouble ?: 1.0, obj.get("iw")?.asDouble ?: 0.0, obj.get("ih")?.asDouble ?: 0.0,
            count, obj.get("total")?.takeIf { it.isJsonPrimitive }?.asInt, limit,
        )
    }

    private fun node(o: JsonObject, parent: DomNode?, path: List<Int>): DomNode {
        val attrs = linkedMapOf<String, String>()
        o.getAsJsonObject("a")?.entrySet()?.forEach { attrs[it.key] = it.value.asString }
        val r = o.getAsJsonArray("r")?.map { it.asDouble }?.takeIf { it.size == 4 }?.toDoubleArray() ?: DoubleArray(4)
        val n = DomNode(o.get("t").asString, attrs, o.get("x")?.asString.orEmpty(), r, path, parent)
        o.getAsJsonArray("k")?.forEachIndexed { i, k -> n.add(node(k.asJsonObject, n, path + i)) }
        return n
    }

    /**
     * The script whose `JSON.stringify` result [parse] reads: the whole DOM as compact JSON (tag, attributes, own text, rect) with
     * a cap of [limit] elements and the real element count in `total`. Text is the element's own text nodes, 80 characters at most.
     */
    fun script(limit: Int = DEFAULT_LIMIT): String =
        "JSON.stringify((function(){var MAX=$limit,n=0;function w(e,d){if(n>=MAX)return null;n++;var r=e.getBoundingClientRect(),a={};" +
            "for(var i=0;i<e.attributes.length;i++){a[e.attributes[i].name]=e.attributes[i].value.slice(0,200)}" +
            "var t='';for(var c=e.firstChild;c;c=c.nextSibling){if(c.nodeType==3)t+=c.nodeValue}" +
            "t=t.replace(/\\s+/g,' ').trim().slice(0,80);" +
            "var o={t:e.tagName.toLowerCase(),a:a,x:t,r:[Math.round(r.x*10)/10,Math.round(r.y*10)/10,Math.round(r.width*10)/10,Math.round(r.height*10)/10],k:[]};" +
            "if(d<40)for(var c=e.firstElementChild;c;c=c.nextElementSibling){var s=w(c,d+1);if(s)o.k.push(s)}return o}" +
            "return {dpr:window.devicePixelRatio,iw:innerWidth,ih:innerHeight,sx:scrollX,sy:scrollY,root:w(document.documentElement,0),count:n," +
            "total:document.getElementsByTagName('*').length}})())"
}

/**
 * Maps a viewport relative CSS rect to device coordinates: the web view's [webViewBounds] (from `webview list`) scaled by
 * bounds.width / viewport width (the html element width, which is exact where innerWidth is rounded), so the device pixel ratio is not needed (Android: pixels, iOS: points; verified on both).
 */
object WebRectMapper {
    fun toDevice(rect: DoubleArray, cssViewportWidth: Double, webViewBounds: Rect): Rect? {
        if (rect.size != 4 || cssViewportWidth <= 0 || webViewBounds.width <= 0) return null
        if (rect[2] <= 0 || rect[3] <= 0) return null // not rendered
        val f = webViewBounds.width / cssViewportWidth
        return Rect(
            (webViewBounds.x + rect[0] * f).roundToInt(), (webViewBounds.y + rect[1] * f).roundToInt(),
            (rect[2] * f).roundToInt().coerceAtLeast(1), (rect[3] * f).roundToInt().coerceAtLeast(1),
        )
    }

    /** The part of [rect] inside [webViewBounds], or null when none of it is visible (scrolled out, or under the native toolbar). */
    fun clip(rect: Rect, webViewBounds: Rect): Rect? {
        val x1 = maxOf(rect.x, webViewBounds.x)
        val y1 = maxOf(rect.y, webViewBounds.y)
        val x2 = minOf(rect.x + rect.width, webViewBounds.x + webViewBounds.width)
        val y2 = minOf(rect.y + rect.height, webViewBounds.y + webViewBounds.height)
        return if (x2 > x1 && y2 > y1) Rect(x1, y1, x2 - x1, y2 - y1) else null
    }
}
