package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform

/**
 * Evaluates an Android `UiSelector` expression (the value of the `-android uiautomator` strategy) against a hierarchy.
 *
 * Supported: `resourceId`, `resourceIdMatches`, `text`, `textContains`, `textStartsWith`, `textMatches`, `description`,
 * `descriptionContains`, `descriptionStartsWith`, `descriptionMatches`, `className`, `classNameMatches`, `packageName`,
 * `packageNameMatches`, the booleans `clickable`, `checkable`, `checked`, `enabled`, `focusable`, `focused`,
 * `longClickable`, `scrollable`, `selected`, `index(n)` (position among the siblings, from 0), `instance(n)` (the n-th
 * match in document order, from 0) and `childSelector(...)` (a matching descendant). `...Matches` are whole-string regexes.
 * Anything else (`fromParent`, `UiScrollable`) is refused with a "not supported yet" message.
 */
internal object UiSelectorQuery {

    private class Spec(
        val conditions: Map<String, (UiNode) -> Boolean>, val instance: Int?, val child: Spec?,
        val required: Set<String> = emptySet(),
    ) {
        fun allRequired(): Set<String> = required + (child?.allRequired() ?: emptySet())
    }

    /** The real UiSelector string methods; UiSelector has no classNameContains, resourceIdStartsWith and so on. */
    private val stringMethods: Map<String, Pair<String, String?>> = mapOf(
        "text" to ("text" to null), "textContains" to ("text" to "Contains"),
        "textStartsWith" to ("text" to "StartsWith"), "textMatches" to ("text" to "Matches"),
        "description" to ("description" to null), "descriptionContains" to ("description" to "Contains"),
        "descriptionStartsWith" to ("description" to "StartsWith"), "descriptionMatches" to ("description" to "Matches"),
        "className" to ("className" to null), "classNameMatches" to ("className" to "Matches"),
        "resourceId" to ("resourceId" to null), "resourceIdMatches" to ("resourceId" to "Matches"),
        "packageName" to ("packageName" to null), "packageNameMatches" to ("packageName" to "Matches"),
    )

    private val stringAttributes: Map<String, (UiNode) -> String> = mapOf(
        "text" to { n -> n.attributes["text"].orEmpty() },
        "description" to { n -> n.attributes["content-desc"].orEmpty() },
        "className" to { n -> n.rawType },
        "resourceId" to { n -> n.attributes["resource-id"].orEmpty() },
        "packageName" to { n -> n.attributes["package"].orEmpty() },
    )

    private val booleanAttributes = mapOf(
        "clickable" to "clickable", "checkable" to "checkable", "checked" to "checked", "enabled" to "enabled",
        "focusable" to "focusable", "focused" to "focused", "longClickable" to "long-clickable",
        "scrollable" to "scrollable", "selected" to "selected",
    )

    fun find(expression: String, root: UiNode): List<UiNode> {
        require(root.platform == Platform.ANDROID) { "-android uiautomator needs an Android screen, this one is iOS" }
        val cursor = Cursor(expression.trim().trimEnd(';'))
        val spec = parseSelector(cursor)
        cursor.requireEnd()
        val nodes = root.walk().toList()
        for (attr in spec.allRequired()) {
            require(nodes.any { attr in it.attributes }) { "not supported yet: '$attr' is not in this hierarchy dump" }
        }
        return evaluate(spec, nodes.filter { !it.isRoot })
    }

    private fun evaluate(spec: Spec, scope: List<UiNode>): List<UiNode> {
        var matches = scope.filter { n -> spec.conditions.values.all { it(n) } }
        spec.instance?.let { matches = listOfNotNull(matches.getOrNull(it)) }
        val child = spec.child ?: return matches
        val result = LinkedHashSet<UiNode>()
        for (n in matches) result += evaluate(child, n.walk().filter { it !== n }.toList())
        return result.toList()
    }

    private fun parseSelector(c: Cursor): Spec {
        c.skipWhitespace()
        val save = c.i
        var word = c.identifier()
        if (word == "new") word = c.identifier()
        if (word == "UiScrollable" || word == "UiCollection") throw IllegalArgumentException("not supported yet: $word")
        require(word == "UiSelector") { "Expected new UiSelector()... at: ${c.text.substring(save).take(30)}" }
        c.expect('(')
        c.expect(')')
        val conditions = linkedMapOf<String, (UiNode) -> Boolean>()
        var instance: Int? = null
        var child: Spec? = null
        val required = linkedSetOf<String>()
        while (c.eat('.')) {
            val method = c.identifier() ?: throw IllegalArgumentException("Expected a method name after '.'")
            c.expect('(')
            when {
                method == "instance" -> instance = integer(c, method)
                method == "index" -> { val n = integer(c, method); conditions[method] = { it.parent?.children?.indexOf(it) == n } }
                method == "childSelector" -> child = parseSelector(c)
                method == "fromParent" -> throw IllegalArgumentException("not supported yet: UiSelector.fromParent(...)")
                method in booleanAttributes -> {
                    val flag = when (c.identifier()) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("$method() takes true or false") }
                    val attr = booleanAttributes.getValue(method)
                    required += attr
                    conditions[method] = { (it.attributes[attr] == "true") == flag }
                }
                else -> {
                    if (method.startsWith("packageName")) required += "package"
                    conditions[method] = stringCondition(method, c.literal())
                }
            }
            c.expect(')')
        }
        return Spec(conditions, instance, child, required)
    }

    private fun integer(c: Cursor, method: String): Int {
        c.skipWhitespace()
        val start = c.i
        if (c.text.getOrNull(c.i) == '-') c.i++
        while (c.i < c.text.length && c.text[c.i].isDigit()) c.i++
        return c.text.substring(start, c.i).toIntOrNull() ?: throw IllegalArgumentException("$method() takes a number")
    }

    private fun stringCondition(method: String, value: String): (UiNode) -> Boolean {
        val (base, suffix) = stringMethods[method] ?: throw IllegalArgumentException("not supported yet: UiSelector.$method(...)")
        val get = stringAttributes.getValue(base)
        return when (suffix) {
            null -> { n -> get(n) == value }
            "Contains" -> { n -> get(n).contains(value) }
            "StartsWith" -> { n -> get(n).startsWith(value) }
            else -> {
                val regex = try { Regex(value) } catch (e: Exception) { throw IllegalArgumentException("Invalid regex in $method(): ${e.message?.lineSequence()?.first()}") }
                ({ n -> regex.matches(get(n)) })
            }
        }
    }
}
