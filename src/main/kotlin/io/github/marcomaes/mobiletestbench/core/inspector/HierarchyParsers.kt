package io.github.marcomaes.mobiletestbench.core.inspector

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.Rect
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object SafeXml {
    fun parse(xml: String): Element {
        val start = xml.indexOf('<')
        require(start >= 0) { "No XML found in hierarchy output" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        // uiautomator sometimes prints a status line after the XML.
        val end = xml.lastIndexOf('>')
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml.substring(start, end + 1))))
        return document.documentElement
    }

    fun Element.attributeMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            map[a.nodeName] = a.nodeValue
        }
        return map
    }

    fun Element.childElements(): List<Element> {
        val list = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(list::add)
        return list
    }
}

/** Parses `uiautomator dump` output. */
object UiAutomatorParser {
    private val boundsRegex = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parseBounds(value: String?): Rect? {
        val m = value?.let { boundsRegex.find(it) } ?: return null
        val (x1, y1, x2, y2) = m.destructured
        return Rect(x1.toInt(), y1.toInt(), x2.toInt() - x1.toInt(), y2.toInt() - y1.toInt())
    }

    fun parse(xml: String): UiNode {
        val rootElement = SafeXml.parse(xml)
        val root = UiNode(Platform.ANDROID, "hierarchy", with(SafeXml) { rootElement.attributeMap() }, null)
        with(SafeXml) { rootElement.childElements() }.forEach { root.addChild(convert(it)) }
        return root
    }

    private fun convert(element: Element): UiNode {
        val attrs = with(SafeXml) { element.attributeMap() }
        val node = UiNode(Platform.ANDROID, attrs["class"]?.ifEmpty { null } ?: "android.view.View", attrs, parseBounds(attrs["bounds"]))
        with(SafeXml) { element.childElements() }.filter { it.tagName == "node" }.forEach { node.addChild(convert(it)) }
        return node
    }
}

/** Parses WebDriverAgent `/source` XML. */
object WdaSourceParser {
    fun parse(xml: String): UiNode {
        var rootElement = SafeXml.parse(xml)
        if (rootElement.tagName == "AppiumAUT") {
            rootElement = with(SafeXml) { rootElement.childElements() }.firstOrNull() ?: rootElement
        }
        return convert(rootElement)
    }

    private fun convert(element: Element): UiNode {
        val attrs = with(SafeXml) { element.attributeMap() }
        val bounds = run {
            val x = attrs["x"]?.toDoubleOrNull()
            val y = attrs["y"]?.toDoubleOrNull()
            val w = attrs["width"]?.toDoubleOrNull()
            val h = attrs["height"]?.toDoubleOrNull()
            if (x != null && y != null && w != null && h != null) Rect(x.toInt(), y.toInt(), w.toInt(), h.toInt()) else null
        }
        val node = UiNode(Platform.IOS, attrs["type"]?.ifEmpty { null } ?: element.tagName, attrs, bounds)
        with(SafeXml) { element.childElements() }.forEach { node.addChild(convert(it)) }
        return node
    }
}

/**
 * Parses `mobilecli dump ui` JSON of an iOS device. Element types come without the
 * "XCUIElementType" prefix; it is restored so locators and XPath match WDA output.
 * The dump has several top level elements, so a synthetic root is added.
 */
object MobilecliDumpParser {
    fun parse(json: String): UiNode {
        val root = JsonParser.parseString(json).asJsonObject
        if (root["status"]?.asString == "error") error(root["error"]?.asString ?: "mobilecli dump failed")
        val elements = root["data"]?.asJsonObject?.get("elements")?.asJsonArray
            ?: error("mobilecli dump has no elements")
        val node = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        elements.forEach { node.addChild(convert(it.asJsonObject)) }
        return node
    }

    private fun convert(obj: JsonObject): UiNode {
        fun str(key: String) = obj[key]?.takeIf { it.isJsonPrimitive }?.asString
        val type = str("type").orEmpty()
        // WDA reports name = accessibilityIdentifier, else the label; mobilecli gives identifier explicitly.
        val attrs = linkedMapOf<String, String>()
        (str("label"))?.let { attrs["label"] = it }
        (str("identifier")?.ifEmpty { null } ?: str("name"))?.let { attrs["name"] = it }
        str("value")?.let { attrs["value"] = it }
        listOf("enabled", "visible").forEach { key -> obj[key]?.takeIf { it.isJsonPrimitive }?.let { attrs[key] = it.asString } }
        val bounds = obj["rect"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { r ->
            val x = r["x"]?.asDouble
            val y = r["y"]?.asDouble
            val w = r["width"]?.asDouble
            val h = r["height"]?.asDouble
            if (x != null && y != null && w != null && h != null) Rect(x.toInt(), y.toInt(), w.toInt(), h.toInt()) else null
        }
        val rawType = if (type.isEmpty() || type.startsWith("XCUIElementType")) type.ifEmpty { "XCUIElementTypeOther" } else "XCUIElementType$type"
        val node = UiNode(Platform.IOS, rawType, attrs, bounds)
        obj["children"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { node.addChild(convert(it.asJsonObject)) }
        return node
    }
}

/**
 * Parses `mobilecli dump ui --format raw` of an Android device: the same data as
 * `uiautomator dump`, as JSON nested in `data.rawData`. Used when uiautomator itself fails
 * (it is killed on some emulator images).
 */
object MobilecliAndroidRawParser {
    private val copied = listOf(
        "class", "text", "hint", "content-desc", "resource-id",
        "focused", "enabled", "checked", "checkable", "clickable", "selected", "visible",
    )

    fun parse(json: String): UiNode {
        val outer = JsonParser.parseString(json).asJsonObject
        if (outer["status"]?.asString == "error") error(outer["error"]?.asString ?: "mobilecli dump failed")
        val raw = outer["data"]?.asJsonObject?.get("rawData")?.asString ?: error("mobilecli dump has no rawData")
        val hierarchy = JsonParser.parseString(raw).asJsonObject["hierarchy"]?.asJsonArray ?: error("mobilecli rawData has no hierarchy")
        val root = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)
        hierarchy.forEach { root.addChild(convert(it.asJsonObject)) }
        return root
    }

    private fun convert(obj: JsonObject): UiNode {
        val attrs = linkedMapOf<String, String>()
        copied.forEach { key -> obj[key]?.takeIf { it.isJsonPrimitive }?.let { attrs[key] = it.asString } }
        val bounds = obj["rect"]?.takeIf { it.isJsonObject }?.asJsonObject?.let { r ->
            val x = r["x"]?.asInt
            val y = r["y"]?.asInt
            val w = r["width"]?.asInt
            val h = r["height"]?.asInt
            if (x != null && y != null && w != null && h != null) Rect(x, y, w, h) else null
        }
        val node = UiNode(Platform.ANDROID, attrs["class"]?.ifEmpty { null } ?: "android.view.View", attrs, bounds)
        obj["children"]?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { node.addChild(convert(it.asJsonObject)) }
        return node
    }
}
