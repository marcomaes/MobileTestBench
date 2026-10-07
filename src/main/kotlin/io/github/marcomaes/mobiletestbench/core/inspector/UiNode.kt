package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.Rect

/**
 * One element of the UI hierarchy. The normalized accessors (type, label, identifier,
 * resourceId, text, value) mirror mobilewright's ViewNode so the locator logic matches
 * what Mobilewright Inspector shows.
 */
class UiNode(
    val platform: Platform,
    /** Tag as used in XPath: "android.widget.Button" or "XCUIElementTypeButton". */
    val rawType: String,
    val attributes: Map<String, String>,
    val bounds: Rect?,
) {
    private val _children = mutableListOf<UiNode>()
    val children: List<UiNode> get() = _children

    var parent: UiNode? = null
        private set

    val isRoot: Boolean get() = parent == null

    fun addChild(child: UiNode) {
        child.parent = this
        _children += child
    }

    /** Short type: "Button", "TextView", "StaticText". */
    val type: String
        get() = when (platform) {
            Platform.ANDROID -> rawType.substringAfterLast('.')
            Platform.IOS -> rawType.removePrefix("XCUIElementType")
        }

    val label: String?
        get() = when (platform) {
            Platform.ANDROID -> attr("content-desc")
            Platform.IOS -> attr("label")
        }

    /**
     * iOS accessibilityIdentifier. XCUITest reports the label as "name" when no identifier
     * is set, so name is only treated as an identifier when it differs from the label.
     */
    val identifier: String?
        get() = when (platform) {
            Platform.ANDROID -> null
            Platform.IOS -> attr("name")?.takeIf { it != attr("label") }
        }

    val resourceId: String?
        get() = if (platform == Platform.ANDROID) attr("resource-id") else null

    val text: String?
        get() = if (platform == Platform.ANDROID) attr("text") else null

    val value: String?
        get() = if (platform == Platform.IOS) attr("value") else null

    val isVisible: Boolean
        get() {
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) return false
            return when (platform) {
                Platform.ANDROID -> attr("visible-to-user") != "false" && attr("visible") != "false"
                Platform.IOS -> attr("visible") != "false"
            }
        }

    fun attr(name: String): String? = attributes[name]?.takeIf { it.isNotEmpty() }

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (child in children) yieldAll(child.walk())
    }

    val depth: Int get() = generateSequence(parent) { it.parent }.count()

    /** 1-based position among siblings with the same tag, as used in XPath. */
    fun xpathIndex(): Int {
        val siblings = parent?.children ?: return 1
        return siblings.filter { it.rawType == rawType }.indexOf(this) + 1
    }

    fun absoluteXPath(): String {
        val chain = generateSequence(this) { it.parent }.toList().reversed()
        return chain.joinToString(separator = "", prefix = "") { node ->
            if (node.isRoot) "/${node.rawType}" else "/${node.rawType}[${node.xpathIndex()}]"
        }
    }

    /** Text shown in the inspector tree. */
    fun displayLabel(): String = buildString {
        append(type)
        val caption = label ?: text ?: value
        if (caption != null) append("  \"").append(caption.take(40)).append('"')
        val id = identifier ?: resourceId?.substringAfter(":id/")
        if (id != null) append("  #").append(id)
    }

    override fun toString(): String = displayLabel()
}

object HierarchyQueries {
    /** The smallest visible node containing the point; on ties the deepest one wins. */
    fun deepestAt(root: UiNode, x: Int, y: Int): UiNode? {
        var best: UiNode? = null
        for (node in root.walk()) {
            if (node.isRoot) continue
            val b = node.bounds ?: continue
            if (!node.isVisible || !b.contains(x, y)) continue
            val current = best
            if (current == null || b.area <= current.bounds!!.area) best = node
        }
        return best
    }
}
