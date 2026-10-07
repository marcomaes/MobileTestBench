package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform

/**
 * A subset of the XCUITest class chain (`-ios class chain`): segments separated by `/`, each a type
 * (`XCUIElementTypeButton`, or `*` for any), `**` for "any depth below", and per segment any number of `[...]` filters
 * applied in order: ``[`predicate`]`` (see [IosPredicateQuery]) or `[n]` (1-based position among the elements found
 * below the current element so far; `-1` is the last). The search starts below the application element.
 * The `$predicate$` descendant condition is refused as not supported yet.
 */
internal object IosClassChainQuery {

    private sealed interface Filter
    private class ByPredicate(val predicate: IosPredicateQuery.Predicate) : Filter
    private class ByIndex(val index: Int) : Filter
    private class Segment(val type: String, val filters: List<Filter>)

    fun find(expression: String, root: UiNode): List<UiNode> {
        require(root.platform == Platform.IOS) { "-ios class chain needs an iOS screen, this one is Android" }
        val segments = parse(expression)
        var contexts: List<UiNode> = listOf(root)
        var deep = false
        for (segment in segments) {
            if (segment.type == "**") { deep = true; continue }
            val next = LinkedHashSet<UiNode>()
            for (context in contexts) {
                var candidates = (if (deep) context.walk().filter { it !== context }.toList() else context.children)
                    .filter { segment.type == "*" || it.rawType == segment.type }
                for (filter in segment.filters) {
                    candidates = when (filter) {
                        is ByPredicate -> candidates.filter { filter.predicate.test(it) }
                        is ByIndex -> listOfNotNull(if (filter.index > 0) candidates.getOrNull(filter.index - 1) else candidates.getOrNull(candidates.size + filter.index))
                    }
                }
                next += candidates
            }
            deep = false
            contexts = next.toList()
            if (contexts.isEmpty()) return emptyList()
        }
        return contexts
    }

    private fun parse(expression: String): List<Segment> {
        val text = expression.trim()
        require(text.isNotEmpty()) { "Empty class chain" }
        val parts = split(text)
        val segments = parts.map(::parseSegment)
        require(segments.last().type != "**") { "A class chain cannot end with **, name the element type after it" }
        return segments
    }

    /** Splits at `/` outside backticks and brackets. */
    private fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inTick = false
        var depth = 0
        for (c in text) {
            when {
                c == '`' -> { inTick = !inTick; current.append(c) }
                inTick -> current.append(c)
                c == '[' -> { depth++; current.append(c) }
                c == ']' -> { depth--; current.append(c) }
                c == '/' && depth == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        require(!inTick) { "Unbalanced backtick in the class chain" }
        parts += current.toString()
        require(parts.none { it.isBlank() }) { "Empty segment in the class chain (a leading, trailing or doubled /)" }
        return parts
    }

    private fun parseSegment(raw: String): Segment {
        val part = raw.trim()
        val bracket = part.indexOf('[')
        val type = (if (bracket < 0) part else part.substring(0, bracket)).trim()
        require(type == "**" || type == "*" || Regex("""XCUIElementType\w+""").matches(type)) {
            "Unknown element type '$type' in the class chain (expected XCUIElementTypeX, * or **)"
        }
        if (bracket < 0) return Segment(type, emptyList())
        require(type != "**") { "not supported yet: a filter on **" }
        val filters = mutableListOf<Filter>()
        var i = bracket
        while (i < part.length) {
            require(part[i] == '[') { "Unexpected '${part.substring(i).take(20)}' in the class chain" }
            val close = closingBracket(part, i)
            val body = part.substring(i + 1, close).trim()
            filters += when {
                body.startsWith("`") && body.endsWith("`") && body.length >= 2 ->
                    ByPredicate(IosPredicateQuery.parse(body.substring(1, body.length - 1)))
                body.startsWith("$") -> throw IllegalArgumentException("not supported yet: the class chain condition $body")
                else -> ByIndex(body.toIntOrNull()?.takeIf { it != 0 } ?: throw IllegalArgumentException("not supported yet: [$body] in a class chain (expected [n], [-1] or [`predicate`])"))
            }
            i = close + 1
            while (i < part.length && part[i].isWhitespace()) i++
        }
        return Segment(type, filters)
    }

    private fun closingBracket(part: String, open: Int): Int {
        var inTick = false
        var quote: Char? = null
        for (i in open + 1 until part.length) {
            val c = part[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '`' -> inTick = !inTick
                c == ']' && !inTick -> return i
            }
        }
        throw IllegalArgumentException("Missing ] in the class chain")
    }
}
