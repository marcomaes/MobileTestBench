package io.github.marcomaes.mobiletestbench.core.pageobject

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorSuggestion

/** What a locator from the Locators tab is copied as. Stored by [name] in the settings. */
enum class LocatorLanguage(val label: String, val appium: PageObjectLanguage?, val webdriverio: Boolean = false) {
    AS_SHOWN("As shown", null),
    MOBILEWRIGHT("Mobilewright (TypeScript / JavaScript)", null),
    APPIUM_JAVA("Appium Java", PageObjectLanguage.APPIUM_JAVA),
    APPIUM_KOTLIN("Appium Kotlin", PageObjectLanguage.APPIUM_KOTLIN),
    APPIUM_PYTHON("Appium Python", PageObjectLanguage.APPIUM_PYTHON),
    APPIUM_CSHARP("Appium C#", PageObjectLanguage.APPIUM_CSHARP),
    APPIUM_WEBDRIVERIO("Appium JavaScript (WebdriverIO)", null, webdriverio = true);

    override fun toString() = label

    companion object {
        fun fromName(name: String?): LocatorLanguage = entries.firstOrNull { it.name == name } ?: AS_SHOWN
    }
}

/** The text a locator row is copied or inserted as. [note] is set when the row has no form in the chosen language. */
data class LocatorText(val text: String, val note: String? = null)

object LocatorCode {

    /** The locator as code in [language], or null when the row has no form in that language (a Mobilewright row for Java, an Appium row for Mobilewright). */
    fun render(suggestion: LocatorSuggestion, language: LocatorLanguage, wholeLine: Boolean = false): String? {
        val code = renderBare(suggestion, language) ?: return null
        return if (wholeLine) wholeLine(code, suggestion, language) else code
    }

    /**
     * The bare [code] wrapped as a statement: Java/Kotlin `driver.findElement(By.id("x"))`, Python `driver.find_element(AppiumBy.ID, "x")`,
     * C# `driver.FindElement(By.Id("x"))`, WebdriverIO `await $('~x')`. As shown and Mobilewright are already a line (`screen.getByTestId(...)`) and stay as they are.
     */
    fun wholeLine(code: String, suggestion: LocatorSuggestion, language: LocatorLanguage): String = when {
        suggestion.framework != "Appium" -> code
        language.webdriverio -> "await $code"
        language == LocatorLanguage.APPIUM_JAVA || language == LocatorLanguage.APPIUM_KOTLIN -> "driver.findElement($code)"
        language == LocatorLanguage.APPIUM_PYTHON -> "driver.find_element(${code.removePrefix("(").removeSuffix(")")})"
        language == LocatorLanguage.APPIUM_CSHARP -> "driver.FindElement($code)"
        else -> code
    }

    private fun renderBare(suggestion: LocatorSuggestion, language: LocatorLanguage): String? {
        val appium = language.appium
        return when {
            language == LocatorLanguage.AS_SHOWN -> suggestion.expression
            language == LocatorLanguage.MOBILEWRIGHT -> suggestion.expression.takeIf { suggestion.framework == "Mobilewright" }
            appium != null && suggestion.framework == "Appium" ->
                PageObjectGenerator.appiumCode(appium, PageLocator.Appium(AppiumStrategy.fromLocatorName(suggestion.strategy), suggestion.expression))
            language.webdriverio && suggestion.framework == "Appium" ->
                WebdriverIo.element(PageLocator.Appium(AppiumStrategy.fromLocatorName(suggestion.strategy), suggestion.expression))
            else -> null
        }
    }

    /** What to put on the clipboard: the rendered code, or the expression as shown plus a note when there is no form. */
    fun textFor(suggestion: LocatorSuggestion, language: LocatorLanguage, wholeLine: Boolean = false): LocatorText =
        render(suggestion, language, wholeLine)?.let { LocatorText(it) }
            ?: LocatorText(suggestion.expression, "No ${shortName(language)} form for ${if (suggestion.framework == "Mobilewright") "a Mobilewright" else "an Appium"} locator, used as shown")

    /** The menu label of a tree menu item: with a language, the framework part names it ("Copy Appium Java accessibility id"). */
    fun menuLabel(suggestion: LocatorSuggestion, language: LocatorLanguage): String =
        if (language == LocatorLanguage.AS_SHOWN || render(suggestion, language) == null) "Copy ${suggestion.framework} ${suggestion.strategy}"
        else "Copy ${if (language == LocatorLanguage.MOBILEWRIGHT) "Mobilewright" else language.label} ${suggestion.strategy}"

    private fun shortName(language: LocatorLanguage) = if (language == LocatorLanguage.MOBILEWRIGHT) "Mobilewright" else language.label
}

/** Appium locators as WebdriverIO selectors: `$('~login')`. Pure text generation. */
object WebdriverIo {

    /** A single quoted JavaScript string. */
    fun quote(text: String): String =
        "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"

    /** The selector string: `~` for accessibility id, a prefix for the other strategies, the bare XPath. */
    fun selector(l: PageLocator.Appium): String = when (l.strategy) {
        AppiumStrategy.ID -> "id=${l.value}"
        AppiumStrategy.ACCESSIBILITY_ID -> "~${l.value}"
        AppiumStrategy.ANDROID_UIAUTOMATOR -> "android=${l.value}"
        AppiumStrategy.IOS_PREDICATE -> "-ios predicate string:${l.value}"
        AppiumStrategy.IOS_CLASS_CHAIN -> "-ios class chain:${l.value}"
        AppiumStrategy.XPATH -> l.value
    }

    /** `$('~login')`, the element without `await`. */
    fun element(l: PageLocator.Appium): String = "\$(${quote(selector(l))})"
}
