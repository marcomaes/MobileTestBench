package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform

data class LocatorSuggestion(
    val framework: String,
    val strategy: String,
    /** Text that is copied to the clipboard. */
    val expression: String,
    val matchCount: Int,
    val isBest: Boolean = false,
) {
    val isUnique: Boolean get() = matchCount == 1

    /** Why this locator may be a poor choice, or null when nothing is wrong with it. */
    val hint: String?
        get() = when {
            matchCount == 0 -> "Matches nothing on the current screen."
            matchCount > 1 -> "Not unique: matches $matchCount elements."
            strategy == "xpath (absolute)" -> "Absolute path: breaks when the layout changes. Prefer an id."
            strategy.startsWith("xpath") && strategy != "xpath (in parent)" && LocatorHints.isIndexBased(expression) ->
                "Index based XPath: breaks when elements are added, removed or reordered. Prefer an id or a unique attribute."
            framework == "Mobilewright" && (strategy == "label" || strategy == "text") ->
                "Depends on the visible text, so it breaks with another language or changed copy. Prefer a test id."
            else -> null
        }
}

object LocatorHints {

    private val quoted = Regex("\"[^\"]*\"|'[^']*'")
    private val indexBased = Regex("""\[\s*(\d+|last\(\s*\)(\s*-\s*\d+)?|position\(\s*\)[^\]]*)\s*]|@index\s*=""")

    /** True when an XPath selects by position ([3], (//x)[2], [last()], [position()=2], [@index=1]). */
    fun isIndexBased(xpath: String): Boolean = indexBased.containsMatchIn(xpath.replace(quoted, "\"\""))

    /**
     * The hint of [suggestion] plus, when it has a warning, the best other locator in [all] (already in
     * priority order) that is unique and has no warning; one of the same framework wins over another one.
     */
    fun hintWithAlternative(suggestion: LocatorSuggestion, all: List<LocatorSuggestion>): String? {
        val hint = suggestion.hint ?: return null
        val better = all.filter { it !== suggestion && it.isUnique && it.hint == null }
        val next = better.firstOrNull { it.framework == suggestion.framework } ?: better.firstOrNull()
            ?: return hint
        val where = if (next.framework == suggestion.framework) next.strategy else "${next.framework} ${next.strategy}"
        return "$hint Next best: $where, ${next.expression}"
    }
}

/**
 * Mobilewright locator derivation, ported from Mobilewright Inspector's locator-derivation.js.
 * Priority: getByTestId > getByRole > getByLabel > getByText. If mobilewright changes its
 * matching rules, update [ROLE_TYPE_MAP] to stay in sync (checked against @mobilewright/core 0.0.57, `query-engine.js` ROLE_TYPE_MAP).
 */
object MobilewrightLocators {

    enum class Kind { TEST_ID, ROLE, LABEL, TEXT }

    data class Derived(val kind: Kind, val value: String, val name: String? = null)

    val ROLE_TYPE_MAP: Map<String, List<String>> = linkedMapOf(
        "button" to listOf("button", "imagebutton", "floatingactionbutton", "materialbutton", "appcompatbutton"),
        "textfield" to listOf("textfield", "securetextfield", "searchfield", "edittext", "appcompatedittext", "textinputedittext", "reactedittext"),
        "text" to listOf("statictext", "textview", "appcompattextview", "materialtextview", "text", "reacttextview"),
        "image" to listOf("image", "imageview", "appcompatimageview", "shapeableimageview", "reactimageview"),
        "switch" to listOf("switch", "toggle", "togglebutton"),
        "checkbox" to listOf("checkbox"),
        "radio" to listOf("radio", "radiobutton", "appcompatradiobutton"),
        "slider" to listOf("slider", "seekbar"),
        "list" to listOf("table", "collectionview", "listview", "recyclerview", "scrollview", "reactscrollview"),
        "listitem" to listOf("cell", "linearlayout", "relativelayout", "other"),
        "tab" to listOf("tab", "tabbar"),
        "link" to listOf("link"),
        "header" to listOf("navigationbar", "toolbar", "header"),
    )

    fun role(node: UiNode): String? {
        val type = node.type.lowercase()
        if (type == "reactviewgroup") {
            val clickable = node.attr("clickable") == "true" || node.attr("accessible") == "true"
            return if (clickable) "button" else null
        }
        return ROLE_TYPE_MAP.entries.firstOrNull { type in it.value }?.key
    }

    /** All applicable locators in priority order (the first one is what mobilewright prefers). */
    fun deriveAll(node: UiNode): List<Derived> = buildList {
        (node.identifier ?: node.resourceId)?.let { add(Derived(Kind.TEST_ID, it)) }
        role(node)?.let { add(Derived(Kind.ROLE, it, node.label ?: node.text)) }
        node.label?.let { add(Derived(Kind.LABEL, it)) }
        (node.text ?: node.value)?.let { add(Derived(Kind.TEXT, it)) }
    }

    fun derive(node: UiNode): Derived? = deriveAll(node).firstOrNull()

    fun matches(node: UiNode, locator: Derived): Boolean = when (locator.kind) {
        Kind.TEST_ID -> (node.identifier ?: node.resourceId) == locator.value
        Kind.ROLE -> role(node) == locator.value && (locator.name == null || (node.label ?: node.text) == locator.name)
        Kind.LABEL -> node.label == locator.value
        Kind.TEXT -> (node.text ?: node.value) == locator.value
    }

    fun toCode(locator: Derived): String = when (locator.kind) {
        Kind.TEST_ID -> "screen.getByTestId(${js(locator.value)})"
        Kind.ROLE -> if (locator.name != null) {
            "screen.getByRole(${js(locator.value)}, { name: ${js(locator.name)} })"
        } else {
            "screen.getByRole(${js(locator.value)})"
        }
        Kind.LABEL -> "screen.getByLabel(${js(locator.value)})"
        Kind.TEXT -> "screen.getByText(${js(locator.value)})"
    }

    fun js(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}

object LocatorGenerator {

    fun suggest(node: UiNode, root: UiNode): List<LocatorSuggestion> {
        val all = root.walk().filter { !it.isRoot }.toList()
        val result = mutableListOf<LocatorSuggestion>()

        MobilewrightLocators.deriveAll(node).forEachIndexed { index, derived ->
            result += LocatorSuggestion(
                framework = "Mobilewright",
                strategy = derived.kind.name.lowercase().replace('_', ' '),
                expression = MobilewrightLocators.toCode(derived),
                matchCount = all.count { MobilewrightLocators.matches(it, derived) },
                isBest = index == 0,
            )
        }

        // Not unique: offer the same locator inside a parent that makes it unique.
        val scoped = if (result.firstOrNull()?.isUnique == false) ScopedLocators.forNode(node, root) else null
        if (scoped != null) {
            result[0] = result[0].copy(isBest = false)
            result.add(0, LocatorSuggestion(
                framework = "Mobilewright",
                strategy = scoped.child.kind.name.lowercase().replace('_', ' ') + " in parent",
                expression = scoped.code(),
                matchCount = ScopedLocators.within(all, scoped.parentNode, scoped.child).size,
                isBest = true,
            ))
        }

        val appium = when (node.platform) {
            Platform.ANDROID -> androidAppium(node, all)
            Platform.IOS -> iosAppium(node, all)
        }.toMutableList()
        if (scoped != null) scopedXPath(node, scoped.parentNode, root)?.let { xpath ->
            val at = appium.indexOfFirst { it.strategy == "xpath" }.let { if (it >= 0) it + 1 else appium.size }
            appium.add(at, LocatorSuggestion("Appium", "xpath (in parent)", xpath, 1))
        }
        result += appium
        return result
    }

    /**
     * The XPath of [node] inside [parent], defined by the parent's own attribute. Tried in order: the node as a
     * descendant of the parent, then as the nearest following or preceding sibling, then as the nearest following
     * or preceding element in document order (a flattened tree, where a card is not the parent of its button).
     * Only a form that finds exactly [node] is returned.
     */
    private fun scopedXPath(node: UiNode, parent: UiNode, root: UiNode): String? {
        val parentAttr = identifyingAttribute(parent) ?: return null
        val nodeAttr = identifyingAttribute(node) ?: return null
        val from = "//${parent.rawType}[@${parentAttr.first}=${xpathLiteral(parentAttr.second)}]"
        val target = "${node.rawType}[@${nodeAttr.first}=${xpathLiteral(nodeAttr.second)}]"
        return listOf(
            "$from//$target",
            "$from/following-sibling::$target[1]",
            "$from/preceding-sibling::$target[1]",
            "$from/following::$target[1]",
            "$from/preceding::$target[1]",
        ).firstOrNull { runCatching { LocatorQuery.find(it, root) }.getOrNull()?.singleOrNull() === node }
    }

    private fun identifyingAttribute(node: UiNode): Pair<String, String>? = when (node.platform) {
        Platform.ANDROID -> listOf("resource-id", "content-desc", "text")
        Platform.IOS -> listOf("name", "label", "value")
    }.firstNotNullOfOrNull { attr -> node.attr(attr)?.let { attr to it } }

    private fun androidAppium(node: UiNode, all: List<UiNode>): List<LocatorSuggestion> = buildList {
        node.resourceId?.let { id ->
            add(LocatorSuggestion("Appium", "id", id, all.count { it.resourceId == id }))
        }
        node.label?.let { desc ->
            add(LocatorSuggestion("Appium", "accessibility id", desc, all.count { it.label == desc }))
        }
        val (attr, value) = when {
            node.resourceId != null -> "resource-id" to node.resourceId!!
            node.label != null -> "content-desc" to node.label!!
            node.text != null -> "text" to node.text!!
            else -> null to null
        }
        if (attr != null && value != null) {
            val selector = when (attr) {
                "resource-id" -> "new UiSelector().resourceId(${java(value)})"
                "content-desc" -> "new UiSelector().description(${java(value)})"
                else -> "new UiSelector().className(${java(node.rawType)}).text(${java(value)})"
            }
            val uiCount = when (attr) {
                "text" -> all.count { it.rawType == node.rawType && it.attr(attr) == value }
                else -> all.count { it.attr(attr) == value }
            }
            add(LocatorSuggestion("Appium", "-android uiautomator", selector, uiCount))
            add(LocatorSuggestion("Appium", "xpath", "//${node.rawType}[@$attr=${xpathLiteral(value)}]",
                all.count { it.rawType == node.rawType && it.attr(attr) == value }))
        }
        add(LocatorSuggestion("Appium", "xpath (absolute)", node.absoluteXPath(), 1))
    }

    private fun iosAppium(node: UiNode, all: List<UiNode>): List<LocatorSuggestion> = buildList {
        val name = node.attr("name")
        name?.let { n ->
            add(LocatorSuggestion("Appium", "accessibility id", n, all.count { it.attr("name") == n }))
        }
        val (attr, value) = when {
            node.identifier != null -> "name" to node.identifier!!
            node.label != null -> "label" to node.label!!
            node.value != null -> "value" to node.value!!
            name != null -> "name" to name
            else -> null to null
        }
        if (attr != null && value != null) {
            val count = all.count { it.rawType == node.rawType && it.attr(attr) == value }
            add(LocatorSuggestion("Appium", "-ios predicate string",
                "type == ${predicateLiteral(node.rawType)} AND $attr == ${predicateLiteral(value)}", count))
            add(LocatorSuggestion("Appium", "-ios class chain",
                "**/${node.rawType}[`$attr == ${predicateLiteral(value)}`]", count))
            add(LocatorSuggestion("Appium", "xpath", "//${node.rawType}[@$attr=${xpathLiteral(value)}]", count))
        }
        add(LocatorSuggestion("Appium", "xpath (absolute)", node.absoluteXPath(), 1))
    }

    fun java(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun predicateLiteral(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun xpathLiteral(value: String): String = when {
        !value.contains('"') -> "\"$value\""
        !value.contains('\'') -> "'$value'"
        else -> "concat(" + value.split('"').joinToString(", '\"', ") { "\"$it\"" } + ")"
    }
}
