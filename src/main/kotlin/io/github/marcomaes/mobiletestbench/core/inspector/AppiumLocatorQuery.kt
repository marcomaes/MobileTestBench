package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform

enum class AppiumBy(val label: String) {
    ID("id"), ACCESSIBILITY_ID("accessibility id"), ANDROID_UIAUTOMATOR("-android uiautomator"),
    IOS_PREDICATE("-ios predicate string"), IOS_CLASS_CHAIN("-ios class chain"), XPATH("xpath"), CLASS_NAME("class name"),
}

data class AppiumLocator(val by: AppiumBy, val value: String)

/**
 * Evaluates an Appium locator typed in any form people copy: the strategy and value as the Locators tab shows them
 * (`id=com.x:id/y`, `accessibility id=Login`, `-android uiautomator=new UiSelector()...`, `-ios predicate string=...`,
 * `-ios class chain=...`, `xpath=...`), or code: Java/Kotlin `By.id("x")` / `AppiumBy.accessibilityId("x")`, C#
 * `By.Id("x")` / `MobileBy.AccessibilityId("x")`, Python `(AppiumBy.ID, "x")`, each optionally inside
 * `driver.findElement(...)`, `findElements(...)`, `find_element(...)`, `FindElement(...)`.
 * Anything outside the subset is refused with a "not supported yet: ..." message, never answered with a guess.
 */
object AppiumLocatorQuery {

    private val prefixForm = Regex(
        """^(id|accessibility id|-android uiautomator|-ios predicate string|-ios class chain|xpath|class name)\s*=\s*(.*)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val codeHint = Regex("""(?:\b(?:AppiumBy|MobileBy|By)\s*\.\s*\w)|(?:\bfind_?elements?\s*\()""", RegexOption.IGNORE_CASE)
    private val wrapper = Regex("""\bfind_?elements?\s*\(""", RegexOption.IGNORE_CASE)
    private val byToken = Regex("""^(?:AppiumBy|MobileBy|By)\s*\.\s*(\w+)\s*""")

    /** True when [text] is meant as an Appium locator (a strategy prefix or Appium/Selenium code), so it must not be tried as XPath or Mobilewright. */
    fun looksLikeAppium(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("/")) return false
        if (prefixForm.matches(t)) return true
        // Look only before the first string, so a quoted `By.id` inside a Mobilewright text locator does not count.
        val head = t.takeWhile { it != '"' && it != '\'' }
        return codeHint.containsMatchIn(head)
    }

    fun find(text: String, root: UiNode): List<UiNode> = evaluate(parse(text), root)

    fun parse(text: String): AppiumLocator {
        val t = text.trim().trimEnd(';').trim()
        prefixForm.matchEntire(t)?.let { m ->
            val by = AppiumBy.entries.first { it.label.equals(m.groupValues[1], ignoreCase = true) }
            var value = m.groupValues[2].trim()
            if (by == AppiumBy.ID || by == AppiumBy.ACCESSIBILITY_ID || by == AppiumBy.CLASS_NAME) value = unquote(value)
            require(value.isNotEmpty()) { "${by.label}= needs a value" }
            return AppiumLocator(by, value)
        }
        var args = t
        val call = wrapper.find(t)
        if (call != null && t.substring(0, call.range.first).none { it == '"' || it == '\'' }) {
            val open = call.range.last
            val close = LocatorQuery.closingParenthesis(t, open) ?: throw IllegalArgumentException("Missing closing parenthesis")
            val rest = t.substring(close + 1).trim().trimEnd(';').trim()
            require(rest.isEmpty()) { "not supported yet: $rest after the locator, only the locator itself is evaluated" }
            args = t.substring(open + 1, close)
        }
        return parseArguments(args)
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0] && value.indexOf(value[0], 1) == value.length - 1) value.substring(1, value.length - 1) else value

    private fun parseArguments(raw: String): AppiumLocator {
        var a = raw.trim()
        if (a.startsWith("(") && LocatorQuery.closingParenthesis(a, 0) == a.length - 1) a = a.substring(1, a.length - 1).trim()
        a = a.replaceFirst(Regex("""^by\s*=\s*"""), "")
        val m = byToken.find(a) ?: throw IllegalArgumentException("not supported yet: ${a.take(40)}, write the locator as By.id(\"...\"), AppiumBy.accessibilityId(\"...\") or (AppiumBy.ID, \"...\")")
        val by = strategyOf(m.groupValues[1], a.substringBefore(m.groupValues[1]).trim() + m.groupValues[1])
        val c = Cursor(a, m.range.last + 1)
        val value: String
        if (c.eat('(')) {
            value = c.literal()
            if (!c.eat(')')) throw IllegalArgumentException("not supported yet: ${c.rest().take(40)}")
        } else if (c.eat(',')) {
            c.skipWhitespace()
            Regex("""value\s*=\s*""").matchAt(a, c.i)?.let { c.i = it.range.last + 1 }
            value = c.literal()
        } else {
            throw IllegalArgumentException("not supported yet: ${a.take(40)}")
        }
        c.requireEnd()
        require(value.isNotEmpty()) { "The locator value is empty" }
        return AppiumLocator(by, value)
    }

    /** Maps the Java/Kotlin/C# method name or the Python constant to a strategy. */
    private fun strategyOf(name: String, shown: String): AppiumBy = when (name.lowercase().replace("_", "")) {
        "id" -> AppiumBy.ID
        "accessibilityid" -> AppiumBy.ACCESSIBILITY_ID
        "androiduiautomator" -> AppiumBy.ANDROID_UIAUTOMATOR
        "iosnspredicatestring", "iospredicate", "iosnspredicate" -> AppiumBy.IOS_PREDICATE
        "iosclasschain" -> AppiumBy.IOS_CLASS_CHAIN
        "xpath" -> AppiumBy.XPATH
        "classname" -> AppiumBy.CLASS_NAME
        else -> throw IllegalArgumentException("not supported yet: $shown (supported: id, accessibility id, UiSelector, iOS predicate, iOS class chain, xpath, class name)")
    }

    fun evaluate(locator: AppiumLocator, root: UiNode): List<UiNode> {
        val all = root.walk().filter { !it.isRoot }.toList()
        val android = root.platform == Platform.ANDROID
        val value = locator.value
        return when (locator.by) {
            AppiumBy.ID -> all.filter { (if (android) it.attr("resource-id") else it.attr("name")) == value }
            AppiumBy.ACCESSIBILITY_ID -> all.filter { (if (android) it.attr("content-desc") else it.attr("name")) == value }
            AppiumBy.CLASS_NAME -> all.filter { it.rawType == value }
            AppiumBy.XPATH -> LocatorQuery.findXPath(value, root)
            AppiumBy.ANDROID_UIAUTOMATOR -> UiSelectorQuery.find(value, root)
            AppiumBy.IOS_PREDICATE -> IosPredicateQuery.find(value, root)
            AppiumBy.IOS_CLASS_CHAIN -> IosClassChainQuery.find(value, root)
        }
    }
}
