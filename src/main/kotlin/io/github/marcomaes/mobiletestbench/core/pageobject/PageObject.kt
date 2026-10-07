package io.github.marcomaes.mobiletestbench.core.pageobject

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.inspector.AccessibilityAudit
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorGenerator
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorSuggestion
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode

/** What the page object is written for: a framework and a language. Mobilewright is JavaScript only. */
enum class PageObjectLanguage(val label: String, val appium: Boolean, val extension: String) {
    MOBILEWRIGHT_TS("Mobilewright - TypeScript", false, "ts"),
    MOBILEWRIGHT_JS("Mobilewright - JavaScript", false, "js"),
    APPIUM_JAVA("Appium - Java", true, "java"),
    APPIUM_KOTLIN("Appium - Kotlin", true, "kt"),
    APPIUM_PYTHON("Appium - Python", true, "py"),
    APPIUM_CSHARP("Appium - C#", true, "cs");

    override fun toString() = label
}

enum class AppiumStrategy {
    ID, ACCESSIBILITY_ID, ANDROID_UIAUTOMATOR, IOS_PREDICATE, IOS_CLASS_CHAIN, XPATH;

    companion object {
        /** Maps the strategy name of a Locators-tab row (`id`, `accessibility id`, `xpath (in parent)`, ...) to the strategy. */
        fun fromLocatorName(strategy: String): AppiumStrategy = when (strategy) {
            "id" -> ID
            "accessibility id" -> ACCESSIBILITY_ID
            "-android uiautomator" -> ANDROID_UIAUTOMATOR
            "-ios predicate string" -> IOS_PREDICATE
            "-ios class chain" -> IOS_CLASS_CHAIN
            else -> XPATH
        }
    }
}

/** How an element is found: Mobilewright code starting with `screen.`, or an Appium strategy and its value. */
sealed interface PageLocator {
    data class Mobilewright(val expression: String) : PageLocator
    data class Appium(val strategy: AppiumStrategy, val value: String) : PageLocator
}

enum class PageAction { TAP, FILL, READ }

/** Where the locators of a page object live. */
enum class LocatorStyle(val label: String) {
    /** Private fields, the screen or driver comes from the base class: no constructor of its own (a one-liner in Java, Kotlin and C#). */
    BASE_CLASS_FIELDS("Fields, screen/driver from the base class"),
    /** Private getters (properties), the screen or driver comes from the base class. */
    GETTERS("Getters, screen/driver from the base class"),
    /** Private fields filled in the constructor; the class stands alone, without a base class. */
    CONSTRUCTOR_FIELDS("Fields, set in the constructor (no base class)");

    override fun toString() = label
    val usesBaseClass: Boolean get() = this != CONSTRUCTOR_FIELDS
}

data class PageObjectOptions(
    val className: String,
    val language: PageObjectLanguage,
    val style: LocatorStyle = LocatorStyle.BASE_CLASS_FIELDS,
    /** The class the page object extends (for the base-class styles). */
    val baseClass: String = "BasePage",
    val sortByName: Boolean = false,
)

/** One element of a page object: a name, the locator and the methods to generate for it. */
data class PageElement(val name: String, val actions: Set<PageAction>, val locator: PageLocator)

/** An element of the current screen that could go into a page object. [locator] is null when no unique one exists. */
class PageCandidate(
    val node: UiNode,
    val suggestedName: String,
    val actions: Set<PageAction>,
    val locator: PageLocator?,
    /** Controls (buttons, fields, switches) are ticked at first; plain texts are offered but not ticked. */
    val selectedByDefault: Boolean,
) {
    val typeName: String get() = node.type
}

object PageObjectCandidates {

    private val inputTypes = setOf("textfield", "securetextfield", "searchfield", "edittext", "reactedittext")

    /** Appium strategies in order of preference, as named in the Locators tab. */
    private val appiumOrder = listOf(
        "id", "accessibility id", "-android uiautomator", "-ios predicate string", "-ios class chain", "xpath (in parent)", "xpath",
    )

    /** Visible elements with a locator of the chosen framework, in tree order. */
    fun find(root: UiNode, appium: Boolean): List<PageCandidate> {
        val names = NameBook()
        return root.walk()
            .filter { !it.isRoot && it.isVisible }
            .mapNotNull { node ->
                val actions = actionsFor(node) ?: return@mapNotNull null
                val suggestions = LocatorGenerator.suggest(node, root)
                val locator = if (appium) appiumLocator(suggestions) else mobilewrightLocator(suggestions)
                val base = baseName(node) ?: return@mapNotNull null
                PageCandidate(node, names.unique(base), actions, locator, selectedByDefault = actions.any { it != PageAction.READ })
            }
            .toList()
    }

    private fun actionsFor(node: UiNode): Set<PageAction>? {
        val type = node.type.lowercase()
        return when {
            type in inputTypes -> setOf(PageAction.FILL, PageAction.READ)
            AccessibilityAudit.isInteractive(node) -> setOf(PageAction.TAP)
            !(node.text ?: node.label ?: node.value).isNullOrBlank() -> setOf(PageAction.READ)
            else -> null
        }
    }

    private fun mobilewrightLocator(suggestions: List<LocatorSuggestion>): PageLocator? =
        suggestions.firstOrNull { it.framework == "Mobilewright" && it.isUnique }?.let { PageLocator.Mobilewright(it.expression) }

    /** The best unique Appium locator of [node] in [root], or null when it has none. */
    fun appiumLocatorFor(node: UiNode, root: UiNode): PageLocator.Appium? = appiumLocator(LocatorGenerator.suggest(node, root))

    private fun appiumLocator(suggestions: List<LocatorSuggestion>): PageLocator.Appium? {
        for (strategy in appiumOrder) {
            val suggestion = suggestions.firstOrNull { it.framework == "Appium" && it.strategy == strategy && it.isUnique } ?: continue
            return PageLocator.Appium(AppiumStrategy.fromLocatorName(strategy), suggestion.expression)
        }
        return null
    }

    /** Words for the name, from the test id (without the package prefix), else the label or text; plus what kind of element it is. */
    private fun baseName(node: UiNode): String? {
        val source = (node.identifier ?: node.resourceId?.substringAfter(":id/") ?: node.label ?: node.text ?: node.value)
            ?.takeIf { it.isNotBlank() } ?: return null
        val words = source.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.take(5)
        if (words.isEmpty()) return null
        val suffix = kindWord(node)
        val joined = words.joinToString(" ")
        return if (joined.lowercase().endsWith(suffix.lowercase())) joined else "$joined $suffix"
    }

    private fun kindWord(node: UiNode): String {
        val type = node.type.lowercase()
        return when {
            type in inputTypes -> "field"
            type.contains("switch") || type.contains("toggle") -> "switch"
            type.contains("checkbox") -> "checkbox"
            type.contains("button") -> "button"
            type.contains("image") -> "image"
            type == "cell" || type.contains("tab") -> "item"
            AccessibilityAudit.isInteractive(node) -> "button"
            else -> "text"
        }
    }

    /** Hands out names that are unique within one page object. */
    private class NameBook {
        private val used = HashMap<String, Int>()
        fun unique(name: String): String {
            val count = used.merge(name.lowercase(), 1, Int::plus)!!
            return if (count == 1) name else "$name $count"
        }
    }
}

object PageObjectGenerator {

    private const val COMMENT = "Locators are private. Make one public only when a test needs it, for example in an expect or assertion."

    /** The page object class. */
    fun generate(elements: List<PageElement>, o: PageObjectOptions): String {
        val ordered = if (o.sortByName) elements.sortedBy { it.name.lowercase() } else elements
        return when (o.language) {
            PageObjectLanguage.MOBILEWRIGHT_TS -> mobilewright(o, ordered, typescript = true)
            PageObjectLanguage.MOBILEWRIGHT_JS -> mobilewright(o, ordered, typescript = false)
            PageObjectLanguage.APPIUM_JAVA -> java(o, ordered)
            PageObjectLanguage.APPIUM_KOTLIN -> kotlin(o, ordered)
            PageObjectLanguage.APPIUM_PYTHON -> python(o, ordered)
            PageObjectLanguage.APPIUM_CSHARP -> csharp(o, ordered)
        }
    }

    /** The file name for a class: Python files are snake_case modules, the others are named after the class. */
    fun fileName(language: PageObjectLanguage, className: String): String =
        (if (language == PageObjectLanguage.APPIUM_PYTHON) snake(className) else className) + "." + language.extension

    /** The base class that holds the screen or driver, named [PageObjectOptions.baseClass]. */
    fun generateBaseClass(o: PageObjectOptions): String = when (o.language) {
        PageObjectLanguage.MOBILEWRIGHT_TS -> """
            import type { Screen } from 'mobilewright';

            export abstract class ${o.baseClass} {
              constructor(protected readonly screen: Screen) {}
            }

        """.trimIndent()
        PageObjectLanguage.MOBILEWRIGHT_JS -> """
            export class ${o.baseClass} {
              constructor(screen) {
                this.screen = screen;
              }
            }

        """.trimIndent()
        PageObjectLanguage.APPIUM_JAVA -> """
            import io.appium.java_client.AppiumDriver;

            public abstract class ${o.baseClass} {
                protected final AppiumDriver driver;

                protected ${o.baseClass}(AppiumDriver driver) {
                    this.driver = driver;
                }
            }

        """.trimIndent()
        PageObjectLanguage.APPIUM_KOTLIN -> """
            import io.appium.java_client.AppiumDriver

            abstract class ${o.baseClass}(protected val driver: AppiumDriver)

        """.trimIndent()
        PageObjectLanguage.APPIUM_PYTHON -> """
            class ${o.baseClass}:
                def __init__(self, driver):
                    self.driver = driver

        """.trimIndent()
        PageObjectLanguage.APPIUM_CSHARP -> """
            using OpenQA.Selenium.Appium;

            public abstract class ${o.baseClass}
            {
                protected readonly AppiumDriver Driver;

                protected ${o.baseClass}(AppiumDriver driver)
                {
                    Driver = driver;
                }
            }

        """.trimIndent()
    }

    // ---- naming

    /** The words of a name: split at anything that is not a letter or digit, and where a lower case letter meets an upper case one. */
    private fun words(name: String) = name.split(Regex("[^A-Za-z0-9]+")).flatMap { it.split(Regex("(?<=[a-z0-9])(?=[A-Z])")) }.filter { it.isNotEmpty() }

    private fun camel(name: String) = words(name).mapIndexed { i, w -> if (i == 0) w.replaceFirstChar { it.lowercase() } else w.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun pascal(name: String) = words(name).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun snake(name: String) = words(name).joinToString("_") { it.lowercase() }

    /** Keeps an identifier valid when a name starts with a digit. */
    private fun safe(identifier: String) = if (identifier.firstOrNull()?.isDigit() == true) "_$identifier" else identifier

    fun quote(text: String, kotlin: Boolean = false): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        return "\"" + (if (kotlin) escaped.replace("$", "\\$") else escaped) + "\""
    }

    private fun readsValue(e: PageElement) = PageAction.FILL in e.actions

    /** The words of the reading method's name: "welcome text" is read by `getWelcomeText`, not `getWelcomeTextText`. */
    private fun readWords(e: PageElement): String {
        val suffix = if (readsValue(e)) "value" else "text"
        return if (words(e.name).lastOrNull().equals(suffix, ignoreCase = true)) e.name else "${e.name} $suffix"
    }

    // ---- Mobilewright (TypeScript, JavaScript)

    private fun mobilewright(o: PageObjectOptions, elements: List<PageElement>, typescript: Boolean): String = buildString {
        val standalone = o.style == LocatorStyle.CONSTRUCTOR_FIELDS
        fun expression(e: PageElement) = (e.locator as PageLocator.Mobilewright).expression
        // How the page's own methods refer to a locator.
        fun ref(e: PageElement): String = if (typescript) "this.${safe(camel(e.name))}" else "this.#${safe(camel(e.name))}"
        if (standalone) {
            if (typescript) {
                appendLine("import type { Screen } from 'mobilewright';")
                appendLine()
            }
            appendLine("export class ${o.className} {")
        } else {
            appendLine("import { ${o.baseClass} } from './${o.baseClass}';")
            appendLine()
            appendLine("export class ${o.className} extends ${o.baseClass} {")
        }
        appendLine("  // $COMMENT")
        for (e in elements) {
            val f = safe(camel(e.name))
            val name = if (typescript) f else "#$f"
            when (o.style) {
                LocatorStyle.BASE_CLASS_FIELDS ->
                    appendLine(if (typescript) "  private readonly $f = this.${expression(e)};" else "  #$f = this.${expression(e)};")
                LocatorStyle.GETTERS -> {
                    appendLine("  ${if (typescript) "private " else ""}get $name() {")
                    appendLine("    return this.${expression(e)};")
                    appendLine("  }")
                }
                LocatorStyle.CONSTRUCTOR_FIELDS ->
                    appendLine(if (typescript) "  private readonly $f: ReturnType<Screen['getByTestId']>;" else "  #$f;")
            }
        }
        if (standalone) {
            appendLine()
            appendLine(if (typescript) "  constructor(screen: Screen) {" else "  constructor(screen) {")
            elements.forEach { appendLine("    ${ref(it)} = ${expression(it)};") }
            appendLine("  }")
        }
        for (e in elements) {
            val r = ref(e)
            if (PageAction.TAP in e.actions) {
                appendLine()
                appendLine("  async tap${pascal(e.name)}()${if (typescript) ": Promise<void>" else ""} {")
                appendLine("    await $r.tap();")
                appendLine("  }")
            }
            if (PageAction.FILL in e.actions) {
                appendLine()
                appendLine("  async fill${pascal(e.name)}(text${if (typescript) ": string" else ""})${if (typescript) ": Promise<void>" else ""} {")
                appendLine("    await $r.fill(text);")
                appendLine("  }")
            }
            if (PageAction.READ in e.actions) {
                appendLine()
                appendLine("  async get${pascal(readWords(e))}()${if (typescript) ": Promise<string>" else ""} {")
                appendLine("    return $r.${if (readsValue(e)) "getValue" else "getText"}();")
                appendLine("  }")
            }
        }
        appendLine("}")
    }

    // ---- Appium locators

    private fun javaLocator(l: PageLocator.Appium, kotlin: Boolean): String {
        val v = quote(l.value, kotlin)
        return when (l.strategy) {
            AppiumStrategy.ID -> "By.id($v)"
            AppiumStrategy.ACCESSIBILITY_ID -> "AppiumBy.accessibilityId($v)"
            AppiumStrategy.ANDROID_UIAUTOMATOR -> "AppiumBy.androidUIAutomator($v)"
            AppiumStrategy.IOS_PREDICATE -> "AppiumBy.iOSNsPredicateString($v)"
            AppiumStrategy.IOS_CLASS_CHAIN -> "AppiumBy.iOSClassChain($v)"
            AppiumStrategy.XPATH -> "By.xpath($v)"
        }
    }

    /**
     * One Appium locator as code in [language] (an Appium language; other languages give null).
     * Java and Kotlin: `By.id("x")`; Python: `(AppiumBy.ID, "x")`; C#: `By.Id("x")`.
     */
    fun appiumCode(language: PageObjectLanguage, l: PageLocator.Appium): String? = when (language) {
        PageObjectLanguage.APPIUM_JAVA -> javaLocator(l, kotlin = false)
        PageObjectLanguage.APPIUM_KOTLIN -> javaLocator(l, kotlin = true)
        PageObjectLanguage.APPIUM_PYTHON -> pythonLocator(l)
        PageObjectLanguage.APPIUM_CSHARP -> csharpLocator(l)
        else -> null
    }

    // The .NET client (Appium.WebDriver) has no AppiumBy class: the Appium strategies live in MobileBy (checked against its source on GitHub, not against the NuGet package).
    private fun csharpLocator(l: PageLocator.Appium): String {
        val v = quote(l.value)
        return when (l.strategy) {
            AppiumStrategy.ID -> "By.Id($v)"
            AppiumStrategy.ACCESSIBILITY_ID -> "MobileBy.AccessibilityId($v)"
            AppiumStrategy.ANDROID_UIAUTOMATOR -> "MobileBy.AndroidUIAutomator($v)"
            AppiumStrategy.IOS_PREDICATE -> "MobileBy.IosNSPredicate($v)"
            AppiumStrategy.IOS_CLASS_CHAIN -> "MobileBy.IosClassChain($v)"
            AppiumStrategy.XPATH -> "By.XPath($v)"
        }
    }

    private fun pythonLocator(l: PageLocator.Appium): String {
        val by = when (l.strategy) {
            AppiumStrategy.ID -> "ID"
            AppiumStrategy.ACCESSIBILITY_ID -> "ACCESSIBILITY_ID"
            AppiumStrategy.ANDROID_UIAUTOMATOR -> "ANDROID_UIAUTOMATOR"
            AppiumStrategy.IOS_PREDICATE -> "IOS_PREDICATE"
            AppiumStrategy.IOS_CLASS_CHAIN -> "IOS_CLASS_CHAIN"
            AppiumStrategy.XPATH -> "XPATH"
        }
        return "(AppiumBy.$by, ${quote(l.value)})"
    }

    private fun appium(e: PageElement) = e.locator as PageLocator.Appium

    // ---- Appium (Java, Kotlin, Python, C#)

    private fun java(o: PageObjectOptions, elements: List<PageElement>): String = buildString {
        val standalone = o.style == LocatorStyle.CONSTRUCTOR_FIELDS
        fun field(e: PageElement) = safe(camel(e.name))
        fun ref(e: PageElement) = if (o.style == LocatorStyle.GETTERS) "${field(e)}()" else field(e)
        appendLine("import io.appium.java_client.AppiumBy;")
        appendLine("import io.appium.java_client.AppiumDriver;")
        appendLine("import org.openqa.selenium.By;")
        appendLine()
        appendLine("public class ${o.className}${if (standalone) "" else " extends ${o.baseClass}"} {")
        appendLine("    // $COMMENT")
        if (standalone) appendLine("    private final AppiumDriver driver;")
        for (e in elements) when (o.style) {
            LocatorStyle.BASE_CLASS_FIELDS -> appendLine("    private final By ${field(e)} = ${javaLocator(appium(e), kotlin = false)};")
            LocatorStyle.GETTERS -> {
                appendLine("    private By ${field(e)}() {")
                appendLine("        return ${javaLocator(appium(e), kotlin = false)};")
                appendLine("    }")
            }
            LocatorStyle.CONSTRUCTOR_FIELDS -> appendLine("    private final By ${field(e)};")
        }
        appendLine()
        appendLine("    public ${o.className}(AppiumDriver driver) {")
        if (standalone) {
            appendLine("        this.driver = driver;")
            elements.forEach { appendLine("        this.${field(it)} = ${javaLocator(appium(it), kotlin = false)};") }
        } else {
            appendLine("        super(driver);")
        }
        appendLine("    }")
        for (e in elements) {
            val r = ref(e)
            if (PageAction.TAP in e.actions) {
                appendLine()
                appendLine("    public void tap${pascal(e.name)}() {")
                appendLine("        driver.findElement($r).click();")
                appendLine("    }")
            }
            if (PageAction.FILL in e.actions) {
                appendLine()
                appendLine("    public void fill${pascal(e.name)}(String text) {")
                appendLine("        driver.findElement($r).sendKeys(text);")
                appendLine("    }")
            }
            if (PageAction.READ in e.actions) {
                appendLine()
                appendLine("    public String get${pascal(readWords(e))}() {")
                appendLine("        return driver.findElement($r).${if (readsValue(e)) "getAttribute(\"value\")" else "getText()"};")
                appendLine("    }")
            }
        }
        appendLine("}")
    }

    private fun kotlin(o: PageObjectOptions, elements: List<PageElement>): String = buildString {
        val standalone = o.style == LocatorStyle.CONSTRUCTOR_FIELDS
        fun field(e: PageElement) = safe(camel(e.name))
        appendLine("import io.appium.java_client.AppiumBy")
        appendLine("import io.appium.java_client.AppiumDriver")
        appendLine("import org.openqa.selenium.By")
        appendLine()
        appendLine(if (standalone) "class ${o.className}(private val driver: AppiumDriver) {" else "class ${o.className}(driver: AppiumDriver) : ${o.baseClass}(driver) {")
        appendLine("    // $COMMENT")
        for (e in elements) when (o.style) {
            LocatorStyle.BASE_CLASS_FIELDS -> appendLine("    private val ${field(e)}: By = ${javaLocator(appium(e), kotlin = true)}")
            LocatorStyle.GETTERS -> appendLine("    private val ${field(e)}: By get() = ${javaLocator(appium(e), kotlin = true)}")
            LocatorStyle.CONSTRUCTOR_FIELDS -> appendLine("    private val ${field(e)}: By")
        }
        if (standalone) {
            appendLine()
            appendLine("    init {")
            elements.forEach { appendLine("        ${field(it)} = ${javaLocator(appium(it), kotlin = true)}") }
            appendLine("    }")
        }
        for (e in elements) {
            val f = field(e)
            if (PageAction.TAP in e.actions) {
                appendLine()
                appendLine("    fun tap${pascal(e.name)}() {")
                appendLine("        driver.findElement($f).click()")
                appendLine("    }")
            }
            if (PageAction.FILL in e.actions) {
                appendLine()
                appendLine("    fun fill${pascal(e.name)}(text: String) {")
                appendLine("        driver.findElement($f).sendKeys(text)")
                appendLine("    }")
            }
            if (PageAction.READ in e.actions) {
                appendLine()
                appendLine("    fun get${pascal(readWords(e))}(): String =")
                appendLine("        driver.findElement($f).${if (readsValue(e)) "getAttribute(\"value\")" else "text"}")
            }
        }
        appendLine("}")
    }

    private fun python(o: PageObjectOptions, elements: List<PageElement>): String = buildString {
        val standalone = o.style == LocatorStyle.CONSTRUCTOR_FIELDS
        // Constants for fields, lower case attributes for the other styles.
        fun field(e: PageElement) = safe(snake(e.name)).let { if (o.style == LocatorStyle.BASE_CLASS_FIELDS) "_${it.uppercase()}" else "_$it" }
        appendLine("from appium.webdriver.common.appiumby import AppiumBy")
        if (!standalone) appendLine("from ${snake(o.baseClass)} import ${o.baseClass}")
        appendLine()
        appendLine()
        appendLine(if (standalone) "class ${o.className}:" else "class ${o.className}(${o.baseClass}):")
        appendLine("    # $COMMENT")
        when (o.style) {
            LocatorStyle.BASE_CLASS_FIELDS -> elements.forEach { appendLine("    ${field(it)} = ${pythonLocator(appium(it))}") }
            LocatorStyle.GETTERS -> elements.forEach {
                appendLine("    @property")
                appendLine("    def ${field(it)}(self):")
                appendLine("        return ${pythonLocator(appium(it))}")
                appendLine()
            }
            LocatorStyle.CONSTRUCTOR_FIELDS -> {
                appendLine("    def __init__(self, driver):")
                appendLine("        self.driver = driver")
                elements.forEach { appendLine("        self.${field(it)} = ${pythonLocator(appium(it))}") }
            }
        }
        for (e in elements) {
            val f = field(e)
            if (PageAction.TAP in e.actions) {
                appendLine()
                appendLine("    def tap_${snake(e.name)}(self):")
                appendLine("        self.driver.find_element(*self.$f).click()")
            }
            if (PageAction.FILL in e.actions) {
                appendLine()
                appendLine("    def fill_${snake(e.name)}(self, text):")
                appendLine("        self.driver.find_element(*self.$f).send_keys(text)")
            }
            if (PageAction.READ in e.actions) {
                appendLine()
                appendLine("    def get_${snake(readWords(e))}(self):")
                appendLine("        return self.driver.find_element(*self.$f).${if (readsValue(e)) "get_attribute(\"value\")" else "text"}")
            }
        }
    }.trimEnd() + "\n"

    private fun csharp(o: PageObjectOptions, elements: List<PageElement>): String = buildString {
        val standalone = o.style == LocatorStyle.CONSTRUCTOR_FIELDS
        val driver = if (standalone) "_driver" else "Driver"
        fun field(e: PageElement) = if (o.style == LocatorStyle.GETTERS) pascal(e.name) else "_${camel(e.name)}"
        appendLine("using OpenQA.Selenium;")
        appendLine("using OpenQA.Selenium.Appium;")
        appendLine()
        appendLine("public class ${o.className}${if (standalone) "" else " : ${o.baseClass}"}")
        appendLine("{")
        appendLine("    // $COMMENT")
        if (standalone) appendLine("    private readonly AppiumDriver _driver;")
        for (e in elements) when (o.style) {
            LocatorStyle.BASE_CLASS_FIELDS -> appendLine("    private readonly By ${field(e)} = ${csharpLocator(appium(e))};")
            LocatorStyle.GETTERS -> appendLine("    private By ${field(e)} => ${csharpLocator(appium(e))};")
            LocatorStyle.CONSTRUCTOR_FIELDS -> appendLine("    private readonly By ${field(e)};")
        }
        appendLine()
        if (standalone) {
            appendLine("    public ${o.className}(AppiumDriver driver)")
            appendLine("    {")
            appendLine("        _driver = driver;")
            elements.forEach { appendLine("        ${field(it)} = ${csharpLocator(appium(it))};") }
            appendLine("    }")
        } else {
            appendLine("    public ${o.className}(AppiumDriver driver) : base(driver)")
            appendLine("    {")
            appendLine("    }")
        }
        for (e in elements) {
            val f = field(e)
            if (PageAction.TAP in e.actions) {
                appendLine()
                appendLine("    public void Tap${pascal(e.name)}() => $driver.FindElement($f).Click();")
            }
            if (PageAction.FILL in e.actions) {
                appendLine()
                appendLine("    public void Fill${pascal(e.name)}(string text) => $driver.FindElement($f).SendKeys(text);")
            }
            if (PageAction.READ in e.actions) {
                appendLine()
                appendLine("    public string Get${pascal(readWords(e))}() => $driver.FindElement($f).${if (readsValue(e)) "GetAttribute(\"value\")" else "Text"};")
            }
        }
        appendLine("}")
    }
}
