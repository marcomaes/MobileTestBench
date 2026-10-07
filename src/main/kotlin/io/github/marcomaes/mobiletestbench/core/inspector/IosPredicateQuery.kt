package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform
import java.text.Normalizer

/**
 * A subset of NSPredicate as used by the `-ios predicate string` strategy and inside class chains.
 * Fields: `type`, `name`, `label`, `value`, `identifier` (the same as `name`), and the booleans `enabled`, `visible`,
 * `accessible`. Operators: `==` `=` `!=` `<>` `CONTAINS` `BEGINSWITH` `ENDSWITH` `LIKE` (`*` and `?`) `MATCHES` (whole-string
 * regex) `IN {...}`, with the `[c]` and `[d]` modifiers, combined with `AND`/`&&`, `OR`/`||`, `NOT`/`!` and parentheses.
 */
internal object IosPredicateQuery {

    sealed interface Predicate {
        fun test(node: UiNode): Boolean
        /** Node attributes the predicate reads that a hierarchy dump may not provide (the boolean fields). */
        val required: Set<String> get() = emptySet()
    }

    private class And(val parts: List<Predicate>) : Predicate {
        override fun test(node: UiNode) = parts.all { it.test(node) }
        override val required get() = parts.flatMap { it.required }.toSet()
    }
    private class Or(val parts: List<Predicate>) : Predicate {
        override fun test(node: UiNode) = parts.any { it.test(node) }
        override val required get() = parts.flatMap { it.required }.toSet()
    }
    private class Not(val part: Predicate) : Predicate {
        override fun test(node: UiNode) = !part.test(node)
        override val required get() = part.required
    }
    private class Const(val value: Boolean) : Predicate { override fun test(node: UiNode) = value }
    private class Cmp(val get: (UiNode) -> String?, val needs: String? = null, val test: (String?) -> Boolean) : Predicate {
        override fun test(node: UiNode) = test(get(node))
        override val required get() = setOfNotNull(needs)
    }

    private val stringFields: Map<String, (UiNode) -> String?> = mapOf(
        "type" to { n -> n.rawType },
        "name" to { n -> n.attr("name") },
        "identifier" to { n -> n.attr("name") },
        "label" to { n -> n.attr("label") },
        "value" to { n -> n.attr("value") },
    )
    private val booleanFields = setOf("enabled", "visible", "accessible")

    fun find(expression: String, root: UiNode): List<UiNode> {
        require(root.platform == Platform.IOS) { "-ios predicate string needs an iOS screen, this one is Android" }
        val predicate = parse(expression)
        val nodes = root.walk().toList()
        for (attr in predicate.required) {
            require(nodes.any { attr in it.attributes }) { "not supported yet: '$attr' is not in this hierarchy dump" }
        }
        return nodes.filter { !it.isRoot && predicate.test(it) }
    }

    fun parse(expression: String): Predicate {
        val tokens = tokenize(expression)
        require(tokens.isNotEmpty()) { "Empty predicate" }
        val parser = Parser(tokens)
        val result = parser.parseOr()
        require(parser.atEnd) { "not supported yet: ${parser.rest()} in the predicate" }
        return result
    }

    // ---- tokens

    private sealed interface Tok
    private data class Word(val text: String) : Tok { val upper = text.uppercase() }
    private data class Str(val value: String) : Tok
    private data class Num(val text: String) : Tok
    private data class Sym(val text: String) : Tok

    private fun tokenize(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '\'' || c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (true) {
                        require(i < s.length) { "Unterminated string in the predicate" }
                        val d = s[i]
                        if (d == c) { i++; break }
                        if (d == '\\' && i + 1 < s.length) {
                            val e = s[i + 1]
                            sb.append(if (e == 'n') '\n' else if (e == 't') '\t' else e)
                            i += 2
                        } else { sb.append(d); i++ }
                    }
                    out += Str(sb.toString())
                }
                c.isDigit() || (c == '-' && s.getOrNull(i + 1)?.isDigit() == true) -> {
                    val start = i++
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    out += Num(s.substring(start, i))
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
                    out += Word(s.substring(start, i))
                }
                else -> {
                    val two = s.substring(i, minOf(i + 2, s.length))
                    when {
                        two in setOf("==", "!=", "<>", "<=", ">=", "&&", "||") -> { out += Sym(two); i += 2 }
                        c in "=<>!(){},[]" -> { out += Sym(c.toString()); i++ }
                        else -> throw IllegalArgumentException("not supported yet: '$c' in the predicate")
                    }
                }
            }
        }
        return out
    }

    private class Parser(val tokens: List<Tok>) {
        var pos = 0
        val atEnd: Boolean get() = pos >= tokens.size
        fun rest(): String = tokens.drop(pos).joinToString(" ") { tokenText(it) }

        private fun tokenText(t: Tok) = when (t) { is Word -> t.text; is Str -> "'${t.value}'"; is Num -> t.text; is Sym -> t.text }
        private fun peek(): Tok? = tokens.getOrNull(pos)
        private fun isWord(upper: String) = (peek() as? Word)?.upper == upper
        private fun isSym(s: String) = (peek() as? Sym)?.text == s

        fun parseOr(): Predicate {
            val parts = mutableListOf(parseAnd())
            while (isWord("OR") || isSym("||")) { pos++; parts += parseAnd() }
            return if (parts.size == 1) parts[0] else Or(parts)
        }

        private fun parseAnd(): Predicate {
            val parts = mutableListOf(parseNot())
            while (isWord("AND") || isSym("&&")) { pos++; parts += parseNot() }
            return if (parts.size == 1) parts[0] else And(parts)
        }

        private fun parseNot(): Predicate {
            if (isWord("NOT") || isSym("!")) { pos++; return Not(parseNot()) }
            return parsePrimary()
        }

        private fun parsePrimary(): Predicate {
            if (isSym("(")) {
                pos++
                val inner = parseOr()
                require(isSym(")")) { "Missing closing parenthesis in the predicate" }
                pos++
                return inner
            }
            if (isWord("TRUEPREDICATE")) { pos++; return Const(true) }
            if (isWord("FALSEPREDICATE")) { pos++; return Const(false) }
            return parseComparison()
        }

        private fun parseComparison(): Predicate {
            val left = peek() ?: throw IllegalArgumentException("The predicate ends too early")
            if (left !is Word) throw IllegalArgumentException("not supported yet: ${tokenText(left)} on the left of a comparison, write the field first")
            pos++
            val field = left.text
            val isBool = field in booleanFields
            val getString = stringFields[field]
            if (!isBool && getString == null) {
                throw IllegalArgumentException("not supported yet: predicate field '$field' (supported: ${(stringFields.keys + booleanFields).joinToString(", ")})")
            }
            val op = operator()
            val ci = StringBuilder()
            if (isSym("[")) {
                pos++
                val mod = (peek() as? Word)?.text ?: throw IllegalArgumentException("Expected a modifier like [c] after the operator")
                pos++
                require(isSym("]")) { "Missing ] after the modifier" }
                pos++
                require(mod.all { it == 'c' || it == 'd' }) { "not supported yet: the modifier [$mod]" }
                ci.append(mod)
            }
            val caseInsensitive = 'c' in ci
            val noDiacritics = 'd' in ci
            fun norm(v: String): String {
                var r = v
                if (noDiacritics) r = Normalizer.normalize(r, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                if (caseInsensitive) r = r.lowercase()
                return r
            }

            if (isBool) {
                require(op == "==" || op == "!=") { "not supported yet: $op on the boolean field '$field'" }
                val want = booleanLiteral(field)
                return Cmp({ it.attributes[field] }, field) { raw -> ((raw == "true") == want) == (op == "==") }
            }
            val get = getString!!
            if (op == "IN") {
                val values = listLiteral(field).map(::norm)
                return Cmp(get) { v -> v != null && norm(v) in values }
            }
            val literal = peek()
            pos++
            if (literal is Word && literal.upper in setOf("NIL", "NULL")) {
                require(op == "==" || op == "!=") { "not supported yet: $op with nil" }
                return Cmp(get) { v -> (v == null) == (op == "==") }
            }
            val value = when (literal) {
                is Str -> literal.value
                is Num, is Word -> throw IllegalArgumentException("not supported yet: ${tokenText(literal)} for '$field', compare it with a quoted string")
                else -> throw IllegalArgumentException("Expected a quoted string after $op")
            }
            val wanted = norm(value)
            val test: (String) -> Boolean = when (op) {
                "==" , "!=" -> { v -> norm(v) == wanted }
                "CONTAINS" -> { v -> norm(v).contains(wanted) }
                "BEGINSWITH" -> { v -> norm(v).startsWith(wanted) }
                "ENDSWITH" -> { v -> norm(v).endsWith(wanted) }
                "LIKE" -> {
                    val regex = Regex(likeToRegex(wanted), RegexOption.DOT_MATCHES_ALL)
                    ({ v -> regex.matches(norm(v)) })
                }
                "MATCHES" -> {
                    val regex = try { Regex(value, if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()) }
                    catch (e: Exception) { throw IllegalArgumentException("Invalid regex in the predicate: ${e.message?.lineSequence()?.first()}") }
                    ({ v -> regex.matches(v) })
                }
                else -> throw IllegalArgumentException("not supported yet: $op")
            }
            return if (op == "!=") Cmp(get) { v -> v == null || !test(v) } else Cmp(get) { v -> v != null && test(v) }
        }

        private fun operator(): String {
            val t = peek() ?: throw IllegalArgumentException("The predicate ends after a field name")
            pos++
            return when {
                t is Sym && (t.text == "==" || t.text == "=") -> "=="
                t is Sym && (t.text == "!=" || t.text == "<>") -> "!="
                t is Sym -> throw IllegalArgumentException("not supported yet: the operator ${t.text}")
                t is Word && t.upper in setOf("CONTAINS", "BEGINSWITH", "ENDSWITH", "LIKE", "MATCHES", "IN") -> t.upper
                t is Word && t.upper == "NOT" -> throw IllegalArgumentException("not supported yet: NOT after a field, write NOT (field LIKE ...)")
                else -> throw IllegalArgumentException("not supported yet: the operator ${tokenText(t)}")
            }
        }

        private fun booleanLiteral(field: String): Boolean {
            val t = peek() ?: throw IllegalArgumentException("The predicate ends after $field ==")
            pos++
            return when {
                t is Word && t.upper in setOf("TRUE", "YES") -> true
                t is Word && t.upper in setOf("FALSE", "NO") -> false
                t is Num && t.text == "1" -> true
                t is Num && t.text == "0" -> false
                else -> throw IllegalArgumentException("'$field' is a boolean, compare it with true or false")
            }
        }

        private fun listLiteral(field: String): List<String> {
            val open = (peek() as? Sym)?.text
            require(open == "{" || open == "(") { "Expected { after IN" }
            pos++
            val close = if (open == "{") "}" else ")"
            val values = mutableListOf<String>()
            while (true) {
                val t = peek() as? Str ?: throw IllegalArgumentException("not supported yet: IN lists other than quoted strings for '$field'")
                values += t.value
                pos++
                if (isSym(",")) { pos++; continue }
                require(isSym(close)) { "Missing $close after the IN list" }
                pos++
                return values
            }
        }

        private fun likeToRegex(pattern: String): String = buildString {
            for (ch in pattern) when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                else -> append(Regex.escape(ch.toString()))
            }
        }
    }
}
