package io.github.marcomaes.mobiletestbench.core.inspector

/** A locator found in test code, in a form [LocatorQuery.find] understands. */
data class ExtractedLocator(val expression: String)

/**
 * Finds the locator expression in a line (or selection) of test code, so an editor action can try it on the device.
 * It cuts out only the locator: `await screen.getByTestId("a").tap()` gives `screen.getByTestId("a")`,
 * `driver.findElement(By.id("x")).click()` gives `driver.findElement(By.id("x"))`.
 * Recognized: Mobilewright getBy chains (with nth/first/last), Appium/Selenium `By`/`AppiumBy`/`MobileBy` forms (Java, Kotlin, C#,
 * Python constants and tuples, `find_element(...)` wrappers, also the string forms `find_element("xpath", "...")`),
 * quoted values after `id=`, `xpath=` etc. and a quoted XPath string.
 */
object LocatorExtraction {

    private val mobilewrightStart = Regex("""\b(?:screen\s*\.\s*)?getBy(?:TestId|Role|Label|Text)\s*\(""")
    private val chainCall = Regex("""\s*\.\s*(getBy(?:TestId|Role|Label|Text)|nth|first|last)\s*\(""")
    private val wrapperStart = Regex("""\bfind_?elements?\s*\(""", RegexOption.IGNORE_CASE)
    private val byToken = Regex("""\b(?:AppiumBy|MobileBy|By)\s*\.\s*\w+""")
    private val strategyNames = "accessibility id|-android uiautomator|-ios predicate string|-ios class chain|class name|xpath|id"
    private val prefixStart = Regex("""(?<![\w-])($strategyNames)\s*=\s*(["'])""", RegexOption.IGNORE_CASE)
    private val stringForm = Regex("""^\s*(["'])($strategyNames)\1\s*,\s*""", RegexOption.IGNORE_CASE)
    private val prefixedValue = Regex("""^($strategyNames)\s*=\s*\S.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val literalStart = Regex("""["']""")

    /** [text] is a selection (any number of lines) or one line. Null when no locator is found. */
    fun extract(text: String): ExtractedLocator? {
        val flat = text.lines().joinToString(" ") { it.trim() }.trim()
        if (flat.isEmpty()) return null
        val candidates = listOfNotNull(
            wrapperStart.find(flat)?.let { it.range.first to { wrapper(flat, it) } },
            byToken.find(flat)?.let { it.range.first to { byForm(flat, it) } },
            mobilewrightStart.find(flat)?.let { it.range.first to { mobilewright(flat, it) } },
            prefixStart.find(flat)?.let { it.range.first to { prefixed(flat, it) } },
        ).sortedBy { it.first }
        for ((_, build) in candidates) build()?.let { return ExtractedLocator(it) }
        quotedXPath(flat)?.let { return ExtractedLocator(it) }
        // A selection made inside the quotes of an XPath.
        val bare = flat.trim().trim(';')
        if (bare.startsWith("/") || bare.startsWith("(/")) return ExtractedLocator(bare)
        return null
    }

    private fun mobilewright(text: String, start: MatchResult): String {
        val sb = StringBuilder("screen.")
        var next: MatchResult? = start
        var first = true
        while (next != null) {
            val name = Regex("""getBy\w+|nth|first|last""").find(next.value)!!.value
            val open = next.range.last
            val close = LocatorQuery.closingParenthesis(text, open)
            val end = close ?: (text.length - 1)
            if (!first) sb.append('.')
            sb.append(name).append(text.substring(open, end + 1))
            first = false
            if (close == null) break
            next = chainCall.matchAt(text, close + 1)
        }
        return sb.toString()
    }

    private fun wrapper(text: String, m: MatchResult): String? {
        val open = m.range.last
        val close = LocatorQuery.closingParenthesis(text, open) ?: return text.substring(m.range.first)
        val call = text.substring(m.range.first, close + 1)
        val inner = text.substring(open + 1, close)
        stringForm.find(inner)?.let { sf ->
            val value = literalAt(inner, sf.range.last + 1) ?: return call
            return "${sf.groupValues[2].lowercase()}=$value"
        }
        return call
    }

    private fun byForm(text: String, m: MatchResult): String {
        var i = m.range.last + 1
        while (i < text.length && text[i].isWhitespace()) i++
        return when {
            i < text.length && text[i] == '(' -> {
                val close = LocatorQuery.closingParenthesis(text, i) ?: return text.substring(m.range.first)
                text.substring(m.range.first, close + 1)
            }
            i < text.length && text[i] == ',' -> {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                Regex("""value\s*=\s*""").matchAt(text, j)?.let { j = it.range.last + 1 }
                val end = literalEnd(text, j) ?: text.length
                text.substring(m.range.first, end)
            }
            else -> text.substring(m.range.first, m.range.last + 1)
        }
    }

    private fun prefixed(text: String, m: MatchResult): String? {
        val value = literalAt(text, m.range.last) ?: return null
        return "${m.groupValues[1].lowercase()}=$value"
    }

    private fun quotedXPath(text: String): String? {
        var i = 0
        while (i < text.length) {
            val m = literalStart.find(text, i) ?: return null
            val value = literalAt(text, m.range.first) ?: return null
            val end = literalEnd(text, m.range.first) ?: return null
            if (value.startsWith("/") || value.startsWith("(/") || prefixedValue.matches(value)) return value
            i = end
        }
        return null
    }

    /** The unescaped contents of the string literal that starts at [start] (its opening quote), or null if it is not closed. */
    private fun literalAt(text: String, start: Int): String? {
        if (start >= text.length || (text[start] != '"' && text[start] != '\'')) return null
        val end = literalEnd(text, start) ?: return null
        return text.substring(start + 1, end - 1).replace(Regex("""\\(.)""")) { it.groupValues[1] }
    }

    /** The index just after the string literal starting at [start], or null. */
    private fun literalEnd(text: String, start: Int): Int? {
        if (start >= text.length) return null
        val q = text[start]
        if (q != '"' && q != '\'') return null
        var i = start + 1
        while (i < text.length && text[i] != q) i += if (text[i] == '\\') 2 else 1
        return if (i < text.length) i + 1 else null
    }
}
