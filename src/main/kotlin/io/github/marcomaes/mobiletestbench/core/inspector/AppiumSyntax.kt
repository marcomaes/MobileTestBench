package io.github.marcomaes.mobiletestbench.core.inspector

/** A tiny scanner over a locator expression: whitespace, single characters and quoted string literals of Java, Kotlin, Python and C#. */
internal class Cursor(val text: String, var i: Int = 0) {
    val atEnd: Boolean get() = i >= text.length

    fun skipWhitespace() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    fun peek(): Char? {
        skipWhitespace()
        return text.getOrNull(i)
    }

    fun eat(c: Char): Boolean {
        if (peek() != c) return false
        i++
        return true
    }

    fun expect(c: Char) {
        require(eat(c)) { "Expected '$c' at: ${rest().take(30)}" }
    }

    fun rest(): String = text.substring(minOf(i, text.length)).trim()

    /** An identifier (letters, digits, `_`), or null when none follows. */
    fun identifier(): String? {
        skipWhitespace()
        val start = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return if (i > start) text.substring(start, i) else null
    }

    fun requireEnd() {
        skipWhitespace()
        require(atEnd) { "not supported yet: ${rest().take(40)}" }
    }

    /**
     * A quoted string: `"..."` or `'...'` with backslash escapes (Java, Kotlin, Python), Python raw `r"..."`,
     * triple quoted `"""..."""`, and C# verbatim `@"..."` (where `""` is a quote). String interpolation is refused.
     */
    fun literal(): String {
        skipWhitespace()
        var raw = false
        var verbatim = false
        val first = text.getOrNull(i)
        val second = text.getOrNull(i + 1)
        if (second == '"' || second == '\'') {
            when (first) {
                'r', 'R' -> { raw = true; i++ }
                '@' -> { verbatim = true; i++ }
                '$', 'f', 'F' -> throw IllegalArgumentException("not supported yet: an interpolated string, write the value out")
                else -> {}
            }
        }
        val quote = text.getOrNull(i)
        require(quote == '"' || quote == '\'') { "Expected a quoted string at: ${rest().take(30)}" }
        val triple = "$quote$quote$quote"
        if (text.startsWith(triple, i)) {
            val end = text.indexOf(triple, i + 3)
            require(end >= 0) { "Unterminated string" }
            val value = text.substring(i + 3, end)
            i = end + 3
            return value
        }
        i++
        val out = StringBuilder()
        while (true) {
            require(i < text.length) { "Unterminated string" }
            val c = text[i]
            when {
                c == quote && verbatim && text.getOrNull(i + 1) == quote -> { out.append(quote); i += 2 }
                c == quote -> { i++; return out.toString() }
                c == '\\' && !raw && !verbatim -> {
                    require(i + 1 < text.length) { "Unterminated string" }
                    val e = text[i + 1]
                    i += 2
                    when (e) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        '0' -> out.append('\u0000')
                        'u' -> {
                            val hex = text.substring(i, minOf(i + 4, text.length))
                            require(hex.length == 4 && hex.all { it.isLetterOrDigit() }) { "Bad \\u escape" }
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        '\\', '\'', '"' -> out.append(e)
                        else -> { out.append('\\'); out.append(e) }
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
    }
}
