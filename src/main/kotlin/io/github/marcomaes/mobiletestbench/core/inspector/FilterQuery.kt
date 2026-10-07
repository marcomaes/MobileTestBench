package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform

/** One `key=value`, `key!=value` or `key~value` term of the element filter. */
data class AttributeTerm(val key: String, val operator: Operator, val value: String) {
    enum class Operator(val symbol: String) { EQUALS("="), NOT_EQUALS("!="), CONTAINS("~") }

    override fun toString(): String = key + operator.symbol + value
}

/** A term that looks like an attribute term but cannot be applied. */
data class InvalidTerm(val text: String, val reason: String)

/**
 * One group of the filter: every part must hold. The plain parts ([freeText], [terms]) must match; the
 * `not` parts (words written with a leading `-` or `!`) must not. [regexes] come from `/pattern/` words.
 */
data class FilterGroup(
    val freeText: String,
    val terms: List<AttributeTerm>,
    val notTerms: List<AttributeTerm> = emptyList(),
    val notFree: List<String> = emptyList(),
    val regexes: List<Regex> = emptyList(),
    val notRegexes: List<Regex> = emptyList(),
) {
    val isEmpty: Boolean
        get() = freeText.isEmpty() && terms.isEmpty() && notTerms.isEmpty() && notFree.isEmpty() &&
            regexes.isEmpty() && notRegexes.isEmpty()
}

/**
 * The text of the filter field, split into groups of free text and attribute terms.
 * Terms are separated by spaces; a value with spaces is written in double quotes (`text="Log in"`).
 * Every other word is free text; the free words are joined again with single spaces.
 * A leading `-` or `!` negates a word (`-error`, `-clickable=true`), `/pattern/` is a case-insensitive
 * regular expression, and the words `OR` or `|` start a new group: an element matches when any group does.
 */
data class FilterQuery(
    val groups: List<FilterGroup>,
    val invalid: List<InvalidTerm>,
) {
    /** Free text of the first group; the whole query when there is no `OR`. */
    val freeText: String get() = groups.firstOrNull()?.freeText.orEmpty()

    /** Attribute terms of the first group; the whole query when there is no `OR`. */
    val terms: List<AttributeTerm> get() = groups.firstOrNull()?.terms.orEmpty()

    val isEmpty: Boolean get() = groups.isEmpty()

    companion object {
        val EMPTY = FilterQuery(emptyList(), emptyList())

        private val keyPattern = Regex("[A-Za-z0-9_.:-]+")

        fun parse(input: String): FilterQuery {
            val groups = mutableListOf<FilterGroup>()
            val free = mutableListOf<String>()
            val notFree = mutableListOf<String>()
            val terms = mutableListOf<AttributeTerm>()
            val notTerms = mutableListOf<AttributeTerm>()
            val regexes = mutableListOf<Regex>()
            val notRegexes = mutableListOf<Regex>()
            val invalid = mutableListOf<InvalidTerm>()
            fun closeGroup() {
                val group = FilterGroup(free.joinToString(" "), terms.toList(), notTerms.toList(), notFree.toList(), regexes.toList(), notRegexes.toList())
                if (!group.isEmpty) groups += group
                free.clear(); notFree.clear(); terms.clear(); notTerms.clear(); regexes.clear(); notRegexes.clear()
            }
            for (token in tokenize(input)) {
                if (token.raw == "OR" || token.raw == "|") {
                    closeGroup()
                    continue
                }
                if (token.unterminatedQuote) {
                    invalid += InvalidTerm(token.raw, "unterminated quote")
                    continue
                }
                val negated = token.raw.length > 1 && (token.raw[0] == '-' || token.raw[0] == '!') && token.raw[1] != '='
                val body = if (negated) token.withoutFirstChar() else token
                when (val parsed = parseToken(body)) {
                    is Parsed.Free -> if (parsed.text.isNotEmpty()) { if (negated) notFree += parsed.text else free += parsed.text }
                    is Parsed.Term -> if (negated) notTerms += parsed.term else terms += parsed.term
                    is Parsed.Re -> if (negated) notRegexes += parsed.regex else regexes += parsed.regex
                    is Parsed.Bad -> invalid += InvalidTerm(token.raw, parsed.reason)
                }
            }
            closeGroup()
            return FilterQuery(groups, invalid)
        }

        private class Token(val raw: String, val text: String, val firstQuote: Int, val unterminatedQuote: Boolean) {
            fun withoutFirstChar() =
                Token(raw.substring(1), text.substring(1), if (firstQuote > 0) firstQuote - 1 else firstQuote, unterminatedQuote)
        }

        private sealed interface Parsed {
            data class Free(val text: String) : Parsed
            data class Term(val term: AttributeTerm) : Parsed
            data class Re(val regex: Regex) : Parsed
            data class Bad(val reason: String) : Parsed
        }

        private fun tokenize(input: String): List<Token> {
            val tokens = mutableListOf<Token>()
            val raw = StringBuilder()
            val text = StringBuilder()
            var firstQuote = -1
            var inQuote = false
            fun flush() {
                if (raw.isNotEmpty()) tokens += Token(raw.toString(), text.toString(), firstQuote, inQuote)
                raw.clear(); text.clear(); firstQuote = -1; inQuote = false
            }
            for (c in input) {
                when {
                    c == '"' -> {
                        if (!inQuote && firstQuote < 0) firstQuote = text.length
                        inQuote = !inQuote
                        raw.append(c)
                    }
                    c.isWhitespace() && !inQuote -> flush()
                    else -> { raw.append(c); text.append(c) }
                }
            }
            flush()
            return tokens
        }

        private fun parseToken(token: Token): Parsed {
            if (token.text.length >= 3 && token.text.startsWith("/") && token.text.endsWith("/")) {
                return try {
                    Parsed.Re(Regex(token.text.substring(1, token.text.length - 1), RegexOption.IGNORE_CASE))
                } catch (e: IllegalArgumentException) {
                    Parsed.Bad("invalid regular expression")
                }
            }
            val head = if (token.firstQuote >= 0) token.text.substring(0, token.firstQuote) else token.text
            val at = findOperator(head) ?: -1
            if (at < 0) return Parsed.Free(token.text)
            val op = when {
                head[at] == '~' -> AttributeTerm.Operator.CONTAINS
                head[at] == '!' -> AttributeTerm.Operator.NOT_EQUALS
                else -> AttributeTerm.Operator.EQUALS
            }
            val key = head.substring(0, at)
            val value = token.text.substring(at + op.symbol.length)
            if (key.isEmpty()) return Parsed.Bad("missing attribute name before ${op.symbol}")
            if (!keyPattern.matches(key)) return Parsed.Free(token.text)
            if (isBoolean(key)) {
                if (op == AttributeTerm.Operator.CONTAINS) return Parsed.Bad("$key takes = or !=, not ~")
                if (value.lowercase() !in setOf("true", "false")) return Parsed.Bad("$key must be true or false")
            }
            return Parsed.Term(AttributeTerm(key, op, value))
        }

        /** Index of the first operator in [head]: `=`, `~`, or `!` directly followed by `=`. */
        private fun findOperator(head: String): Int? {
            for (i in head.indices) {
                if (head[i] == '=' || head[i] == '~') return i
                if (head[i] == '!' && head.getOrNull(i + 1) == '=') return i
            }
            return null
        }

        internal fun isBoolean(key: String): Boolean = key.lowercase() in AttributeMatcher.BOOLEAN_KEYS
    }
}

/** Resolves attribute terms against a [UiNode]; aliases work on both platforms. */
object AttributeMatcher {
    /** Keys that hold true/false. Missing attributes count as false. */
    val BOOLEAN_KEYS = setOf(
        "enabled", "clickable", "focused", "checked", "selected", "visible", "interactive",
        "checkable", "focusable", "scrollable", "password", "long-clickable",
    )

    private val typeKeys = setOf("type", "class")
    private val idKeys = setOf("id", "identifier", "resource-id", "resourceid")
    private val textKeys = setOf("label", "text", "content-desc", "contentdesc", "desc", "value")

    /** The values the key stands for on this node (a missing attribute gives an empty list). */
    fun valuesOf(node: UiNode, key: String): List<String> {
        val k = key.lowercase()
        return when {
            k == "visible" -> listOf(node.isVisible.toString())
            k == "interactive" -> listOf(AccessibilityAudit.isInteractive(node).toString())
            k == "clickable" && node.platform == Platform.IOS -> listOf(AccessibilityAudit.isInteractive(node).toString())
            k in BOOLEAN_KEYS -> listOf((node.attr(k) ?: "false").lowercase())
            k in typeKeys -> listOf(node.type, node.rawType)
            k in idKeys -> listOfNotNull(node.identifier, node.resourceId, node.resourceId?.substringAfter(":id/"))
            k in textKeys -> listOfNotNull(node.label, node.text, node.value)
            else -> listOfNotNull(node.attributes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotEmpty() })
        }
    }

    fun matches(node: UiNode, term: AttributeTerm): Boolean {
        val values = valuesOf(node, term.key)
        return when (term.operator) {
            AttributeTerm.Operator.EQUALS ->
                if (term.value.isEmpty()) values.isEmpty() else values.any { it.equals(term.value, ignoreCase = true) }
            AttributeTerm.Operator.NOT_EQUALS ->
                if (term.value.isEmpty()) values.isNotEmpty() else values.none { it.equals(term.value, ignoreCase = true) }
            AttributeTerm.Operator.CONTAINS -> values.any { it.contains(term.value, ignoreCase = true) }
        }
    }
}
