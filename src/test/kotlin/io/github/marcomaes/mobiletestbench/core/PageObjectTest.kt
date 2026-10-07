package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorStyle
import io.github.marcomaes.mobiletestbench.core.pageobject.PageAction
import io.github.marcomaes.mobiletestbench.core.pageobject.PageElement
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectCandidates
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectGenerator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectLanguage
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageObjectTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)
    private val ios: UiNode = WdaSourceParser.parse(fixture("ios_login.xml"))
    private val android: UiNode = UiAutomatorParser.parse(fixture("android_login.xml"))

    // ---- candidates

    @Test
    fun `candidates are the visible elements with a unique locator, controls ticked first`() {
        val candidates = PageObjectCandidates.find(ios, appium = false)
        val email = candidates.first { it.node.identifier == "email-input" }
        assertEquals("email input field", email.suggestedName.lowercase())
        assertEquals(setOf(PageAction.FILL, PageAction.READ), email.actions)
        assertEquals(PageLocator.Mobilewright("screen.getByTestId('email-input')"), email.locator)
        assertTrue(email.selectedByDefault)
        val signIn = candidates.first { it.node.label == "Sign In" && it.node.type == "Button" }
        assertEquals(setOf(PageAction.TAP), signIn.actions)
        assertTrue(candidates.none { it.node.attr("name") == "Hidden" }) // not visible
    }

    @Test
    fun `plain texts are offered but not ticked`() {
        val text = PageObjectCandidates.find(ios, appium = false).first { it.node.type == "StaticText" }
        assertEquals(setOf(PageAction.READ), text.actions)
        assertFalse(text.selectedByDefault)
    }

    @Test
    fun `appium locators prefer an id, then an accessibility id, and are unique`() {
        val android = PageObjectCandidates.find(android, appium = true).first { it.node.resourceId == "com.example.app:id/email" }
        assertEquals(PageLocator.Appium(AppiumStrategy.ID, "com.example.app:id/email"), android.locator)
        val ios = PageObjectCandidates.find(ios, appium = true).first { it.node.identifier == "email-input" }
        assertEquals(PageLocator.Appium(AppiumStrategy.ACCESSIBILITY_ID, "email-input"), ios.locator)
    }

    @Test
    fun `names are unique within a page and keep to words`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        listOf("a", "b").forEach { i ->
            root.addChild(UiNode(Platform.IOS, "XCUIElementTypeButton", mapOf("name" to "OK-$i", "label" to "Same", "visible" to "true"), Rect(0, 0, 50, 50)))
        }
        // Two buttons with the same label: no unique label locator, but the names must not collide.
        val names = PageObjectCandidates.find(root, appium = false).map { it.suggestedName }
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
    }

    @Test
    fun `an element without a unique locator is offered without one`() {
        val root = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        repeat(2) { root.addChild(UiNode(Platform.IOS, "XCUIElementTypeButton", mapOf("name" to "x", "label" to "Same", "visible" to "true"), Rect(0, 0, 50, 50))) }
        assertTrue(PageObjectCandidates.find(root, appium = false).all { it.locator == null })
    }

    // ---- generator

    private val mw = listOf(
        PageElement("email field", setOf(PageAction.FILL, PageAction.READ), PageLocator.Mobilewright("screen.getByTestId('email-input')")),
        PageElement("login button", setOf(PageAction.TAP), PageLocator.Mobilewright("screen.getByTestId('product-item-2').getByTestId('login')")),
        PageElement("welcome text", setOf(PageAction.READ), PageLocator.Mobilewright("screen.getByText('Welcome')")),
    )

    private val appium = listOf(
        PageElement("email field", setOf(PageAction.FILL, PageAction.READ), PageLocator.Appium(AppiumStrategy.ID, "com.example:id/email")),
        PageElement("login button", setOf(PageAction.TAP), PageLocator.Appium(AppiumStrategy.ACCESSIBILITY_ID, "Log \"in\"")),
        PageElement("welcome text", setOf(PageAction.READ), PageLocator.Appium(AppiumStrategy.XPATH, "//android.widget.TextView[@text=\"Hi\"]")),
    )

    private val comment = "Locators are private. Make one public only when a test needs it, for example in an expect or assertion."

    private fun gen(elements: List<PageElement>, language: PageObjectLanguage, style: LocatorStyle = LocatorStyle.BASE_CLASS_FIELDS, base: String = "BasePage", sort: Boolean = false) =
        PageObjectGenerator.generate(elements, PageObjectOptions("LoginPage", language, style, base, sort))

    @Test
    fun `typescript fields use the screen of the base class and need no constructor`() {
        assertEquals(
            """
            import { BasePage } from './BasePage';

            export class LoginPage extends BasePage {
              // $comment
              private readonly emailField = this.screen.getByTestId('email-input');
              private readonly loginButton = this.screen.getByTestId('product-item-2').getByTestId('login');
              private readonly welcomeText = this.screen.getByText('Welcome');

              async fillEmailField(text: string): Promise<void> {
                await this.emailField.fill(text);
              }

              async getEmailFieldValue(): Promise<string> {
                return this.emailField.getValue();
              }

              async tapLoginButton(): Promise<void> {
                await this.loginButton.tap();
              }

              async getWelcomeText(): Promise<string> {
                return this.welcomeText.getText();
              }
            }

            """.trimIndent(),
            gen(mw, PageObjectLanguage.MOBILEWRIGHT_TS),
        )
    }

    @Test
    fun `typescript getters and constructor styles`() {
        val getters = gen(mw, PageObjectLanguage.MOBILEWRIGHT_TS, LocatorStyle.GETTERS)
        assertTrue(getters.contains("extends BasePage"))
        assertTrue(getters.contains("  private get emailField() {\n    return this.screen.getByTestId('email-input');\n  }"))
        assertFalse(getters.contains("constructor"))

        val standalone = gen(mw, PageObjectLanguage.MOBILEWRIGHT_TS, LocatorStyle.CONSTRUCTOR_FIELDS)
        assertFalse(standalone.contains("extends") || standalone.contains("BasePage"))
        assertTrue(standalone.contains("import type { Screen } from 'mobilewright';"))
        assertTrue(standalone.contains("private readonly emailField: ReturnType<Screen['getByTestId']>;"))
        assertTrue(standalone.contains("constructor(screen: Screen) {\n    this.emailField = screen.getByTestId('email-input');"))
    }

    @Test
    fun `javascript uses private fields`() {
        val code = gen(mw, PageObjectLanguage.MOBILEWRIGHT_JS)
        assertTrue(code.contains("export class LoginPage extends BasePage {"))
        assertTrue(code.contains("  #emailField = this.screen.getByTestId('email-input');"))
        assertTrue(code.contains("await this.#loginButton.tap();"))
        assertFalse(code.contains(": Promise") || code.contains(": string") || code.contains("import type"))
        assertTrue(gen(mw, PageObjectLanguage.MOBILEWRIGHT_JS, LocatorStyle.GETTERS).contains("get #emailField() {"))
    }

    @Test
    fun `the comment about private locators is in every page object exactly once`() {
        for (language in PageObjectLanguage.entries) for (style in LocatorStyle.entries) {
            val code = gen(if (language.appium) appium else mw, language, style)
            assertEquals("$language $style", 1, Regex(Regex.escape(comment)).findAll(code).count())
        }
    }

    @Test
    fun `base classes hold the screen or the driver`() {
        fun base(language: PageObjectLanguage, name: String = "BasePage") =
            PageObjectGenerator.generateBaseClass(PageObjectOptions("LoginPage", language, baseClass = name))
        assertTrue(base(PageObjectLanguage.MOBILEWRIGHT_TS).contains("export abstract class BasePage {\n  constructor(protected readonly screen: Screen) {}"))
        assertTrue(base(PageObjectLanguage.MOBILEWRIGHT_JS).contains("constructor(screen) {\n    this.screen = screen;"))
        assertTrue(base(PageObjectLanguage.APPIUM_JAVA).contains("public abstract class BasePage {\n    protected final AppiumDriver driver;"))
        assertTrue(base(PageObjectLanguage.APPIUM_JAVA).contains("protected BasePage(AppiumDriver driver) {"))
        assertTrue(base(PageObjectLanguage.APPIUM_KOTLIN, "Screens").contains("abstract class Screens(protected val driver: AppiumDriver)"))
        assertTrue(base(PageObjectLanguage.APPIUM_PYTHON).contains("class BasePage:\n    def __init__(self, driver):\n        self.driver = driver"))
        assertTrue(base(PageObjectLanguage.APPIUM_CSHARP).contains("protected readonly AppiumDriver Driver;"))
    }

    @Test
    fun `an own base class name is used for extends and import`() {
        val ts = gen(mw, PageObjectLanguage.MOBILEWRIGHT_TS, base = "MyScreenBase")
        assertTrue(ts.contains("import { MyScreenBase } from './MyScreenBase';"))
        assertTrue(ts.contains("export class LoginPage extends MyScreenBase {"))
        assertTrue(gen(appium, PageObjectLanguage.APPIUM_JAVA, base = "MyScreenBase").contains("public class LoginPage extends MyScreenBase {"))
        assertTrue(gen(appium, PageObjectLanguage.APPIUM_PYTHON, base = "MyScreenBase").contains("from my_screen_base import MyScreenBase\n"))
    }

    @Test
    fun `java page objects`() {
        val fields = gen(appium, PageObjectLanguage.APPIUM_JAVA)
        assertTrue(fields.contains("public class LoginPage extends BasePage {"))
        assertTrue(fields.contains("    private final By emailField = By.id(\"com.example:id/email\");"))
        assertTrue(fields.contains("private final By loginButton = AppiumBy.accessibilityId(\"Log \\\"in\\\"\");"))
        assertTrue(fields.contains("    public LoginPage(AppiumDriver driver) {\n        super(driver);\n    }"))
        assertTrue(fields.contains("public void tapLoginButton() {\n        driver.findElement(loginButton).click();"))
        assertTrue(fields.contains("public String getEmailFieldValue() {\n        return driver.findElement(emailField).getAttribute(\"value\");"))
        assertTrue(fields.contains("public String getWelcomeText() {\n        return driver.findElement(welcomeText).getText();"))
        assertFalse(fields.contains("private final AppiumDriver driver"))

        val getters = gen(appium, PageObjectLanguage.APPIUM_JAVA, LocatorStyle.GETTERS)
        assertTrue(getters.contains("    private By emailField() {\n        return By.id(\"com.example:id/email\");\n    }"))
        assertTrue(getters.contains("driver.findElement(loginButton()).click();"))

        val standalone = gen(appium, PageObjectLanguage.APPIUM_JAVA, LocatorStyle.CONSTRUCTOR_FIELDS)
        assertTrue(standalone.contains("public class LoginPage {"))
        assertTrue(standalone.contains("    private final AppiumDriver driver;\n"))
        assertTrue(standalone.contains("        this.driver = driver;\n        this.emailField = By.id(\"com.example:id/email\");"))
    }

    @Test
    fun `kotlin page objects`() {
        val fields = gen(appium, PageObjectLanguage.APPIUM_KOTLIN)
        assertTrue(fields.contains("class LoginPage(driver: AppiumDriver) : BasePage(driver) {"))
        assertTrue(fields.contains("    private val emailField: By = By.id(\"com.example:id/email\")"))
        assertTrue(fields.contains("fun getWelcomeText(): String =\n        driver.findElement(welcomeText).text"))
        assertTrue(gen(appium, PageObjectLanguage.APPIUM_KOTLIN, LocatorStyle.GETTERS).contains("private val emailField: By get() = By.id("))
        val standalone = gen(appium, PageObjectLanguage.APPIUM_KOTLIN, LocatorStyle.CONSTRUCTOR_FIELDS)
        assertTrue(standalone.contains("class LoginPage(private val driver: AppiumDriver) {"))
        assertTrue(standalone.contains("    init {\n        emailField = By.id("))
        val dollar = PageElement("price", setOf(PageAction.READ), PageLocator.Appium(AppiumStrategy.XPATH, "//x[@text=\"$5\"]"))
        assertTrue(gen(listOf(dollar), PageObjectLanguage.APPIUM_KOTLIN).contains("By.xpath(\"//x[@text=\\\"\\$5\\\"]\")"))
    }

    @Test
    fun `python page objects`() {
        val fields = gen(appium, PageObjectLanguage.APPIUM_PYTHON)
        assertTrue(fields.contains("from base_page import BasePage\n"))
        assertTrue(fields.contains("class LoginPage(BasePage):"))
        assertTrue(fields.contains("    _EMAIL_FIELD = (AppiumBy.ID, \"com.example:id/email\")"))
        assertTrue(fields.contains("def tap_login_button(self):\n        self.driver.find_element(*self._LOGIN_BUTTON).click()"))
        assertFalse(fields.contains("__init__"))
        assertTrue(gen(appium, PageObjectLanguage.APPIUM_PYTHON, LocatorStyle.GETTERS).contains("    @property\n    def _email_field(self):\n        return (AppiumBy.ID,"))
        val standalone = gen(appium, PageObjectLanguage.APPIUM_PYTHON, LocatorStyle.CONSTRUCTOR_FIELDS)
        assertTrue(standalone.contains("class LoginPage:\n"))
        assertTrue(standalone.contains("    def __init__(self, driver):\n        self.driver = driver\n        self._email_field = (AppiumBy.ID,"))
        assertTrue(standalone.endsWith("\n") && !standalone.endsWith("\n\n"))
    }

    @Test
    fun `csharp page objects`() {
        val fields = gen(appium, PageObjectLanguage.APPIUM_CSHARP)
        assertTrue(fields.contains("public class LoginPage : BasePage\n{"))
        assertTrue(fields.contains("    private readonly By _emailField = By.Id(\"com.example:id/email\");"))
        assertTrue(fields.contains("private readonly By _loginButton = MobileBy.AccessibilityId(\"Log \\\"in\\\"\");"))
        assertFalse(fields.contains("AppiumBy"))
        assertTrue(fields.contains("    public LoginPage(AppiumDriver driver) : base(driver)\n    {\n    }"))
        assertTrue(fields.contains("public void TapLoginButton() => Driver.FindElement(_loginButton).Click();"))
        assertTrue(gen(appium, PageObjectLanguage.APPIUM_CSHARP, LocatorStyle.GETTERS).contains("    private By EmailField => By.Id(\"com.example:id/email\");"))
        val standalone = gen(appium, PageObjectLanguage.APPIUM_CSHARP, LocatorStyle.CONSTRUCTOR_FIELDS)
        assertTrue(standalone.contains("private readonly AppiumDriver _driver;"))
        assertTrue(standalone.contains("_driver.FindElement(_loginButton).Click();"))
    }

    @Test
    fun `sorting by name orders the locators and the methods`() {
        val unsorted = gen(mw, PageObjectLanguage.MOBILEWRIGHT_TS)
        assertTrue(unsorted.indexOf("emailField =") < unsorted.indexOf("loginButton =") && unsorted.indexOf("loginButton =") < unsorted.indexOf("welcomeText ="))
        val shuffled = listOf(mw[2], mw[0], mw[1])
        val kept = gen(shuffled, PageObjectLanguage.MOBILEWRIGHT_TS)
        assertTrue(kept.indexOf("welcomeText =") < kept.indexOf("emailField ="))
        val sorted = gen(shuffled, PageObjectLanguage.MOBILEWRIGHT_TS, sort = true)
        assertTrue(sorted.indexOf("emailField =") < sorted.indexOf("loginButton =") && sorted.indexOf("loginButton =") < sorted.indexOf("welcomeText ="))
        assertTrue(sorted.indexOf("async fillEmailField") < sorted.indexOf("async tapLoginButton"))
    }

    @Test
    fun `file names follow the language`() {
        assertEquals("LoginPage.java", PageObjectGenerator.fileName(PageObjectLanguage.APPIUM_JAVA, "LoginPage"))
        assertEquals("login_page.py", PageObjectGenerator.fileName(PageObjectLanguage.APPIUM_PYTHON, "LoginPage"))
        assertEquals("LoginPage.ts", PageObjectGenerator.fileName(PageObjectLanguage.MOBILEWRIGHT_TS, "LoginPage"))
    }

    @Test
    fun `a name that starts with a digit is still a valid identifier`() {
        val el = PageElement("3d view", setOf(PageAction.TAP), PageLocator.Mobilewright("screen.getByTestId('v')"))
        assertTrue(gen(listOf(el), PageObjectLanguage.MOBILEWRIGHT_TS).contains("private readonly _3dView ="))
        assertNotNull(PageObjectLanguage.entries.firstOrNull { it.appium })
    }
}
