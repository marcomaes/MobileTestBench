package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.AppiumBy
import io.github.marcomaes.mobiletestbench.core.inspector.AppiumLocator
import io.github.marcomaes.mobiletestbench.core.inspector.AppiumLocatorQuery
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorGenerator
import io.github.marcomaes.mobiletestbench.core.inspector.LocatorQuery
import io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.PageLocator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectGenerator
import io.github.marcomaes.mobiletestbench.core.pageobject.PageObjectLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppiumLocatorQueryTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)
    private val android: UiNode = UiAutomatorParser.parse(fixture("android_login.xml"))
    private val ios: UiNode = WdaSourceParser.parse(fixture("ios_login.xml"))

    private fun count(expr: String, root: UiNode) = LocatorQuery.find(expr, root).size

    private fun refused(expr: String, root: UiNode, contains: String) {
        try {
            val found = LocatorQuery.find(expr, root)
            fail("expected a refusal for $expr but found ${found.size}")
        } catch (e: IllegalArgumentException) {
            assertTrue("'${e.message}' should contain '$contains'", e.message!!.contains(contains))
        }
    }

    // ---- parsing the forms

    @Test
    fun `bare strategy forms`() {
        assertEquals(AppiumLocator(AppiumBy.ID, "com.x:id/y"), AppiumLocatorQuery.parse("id=com.x:id/y"))
        assertEquals(AppiumLocator(AppiumBy.ACCESSIBILITY_ID, "Login"), AppiumLocatorQuery.parse("accessibility id=Login"))
        assertEquals(AppiumLocator(AppiumBy.ACCESSIBILITY_ID, "Login"), AppiumLocatorQuery.parse("accessibility id = \"Login\""))
        assertEquals(AppiumBy.ANDROID_UIAUTOMATOR, AppiumLocatorQuery.parse("-android uiautomator=new UiSelector().text(\"a\")").by)
        assertEquals("type == \"X\" AND name == \"x\"", AppiumLocatorQuery.parse("-ios predicate string=type == \"X\" AND name == \"x\"").value)
        assertEquals(AppiumBy.IOS_CLASS_CHAIN, AppiumLocatorQuery.parse("-ios class chain=**/XCUIElementTypeButton").by)
        assertEquals(AppiumLocator(AppiumBy.XPATH, "//a[@b='c']"), AppiumLocatorQuery.parse("xpath=//a[@b='c']"))
    }

    @Test
    fun `java and kotlin forms`() {
        assertEquals(AppiumLocator(AppiumBy.ID, "x"), AppiumLocatorQuery.parse("By.id(\"x\")"))
        assertEquals(AppiumLocator(AppiumBy.ACCESSIBILITY_ID, "x"), AppiumLocatorQuery.parse("AppiumBy.accessibilityId(\"x\");"))
        assertEquals(AppiumLocator(AppiumBy.ANDROID_UIAUTOMATOR, "new UiSelector().text(\"a\")"), AppiumLocatorQuery.parse("AppiumBy.androidUIAutomator(\"new UiSelector().text(\\\"a\\\")\")"))
        assertEquals(AppiumBy.IOS_PREDICATE, AppiumLocatorQuery.parse("AppiumBy.iOSNsPredicateString(\"name == 'a'\")").by)
        assertEquals(AppiumBy.IOS_CLASS_CHAIN, AppiumLocatorQuery.parse("AppiumBy.iOSClassChain(\"**/XCUIElementTypeButton\")").by)
        assertEquals(AppiumLocator(AppiumBy.XPATH, "//a"), AppiumLocatorQuery.parse("driver.findElement(By.xpath(\"//a\"))"))
        assertEquals(AppiumLocator(AppiumBy.ID, "x"), AppiumLocatorQuery.parse("val e = driver.findElements(By.id(\"x\"))"))
        assertEquals(AppiumLocator(AppiumBy.CLASS_NAME, "android.widget.Button"), AppiumLocatorQuery.parse("By.className(\"android.widget.Button\")"))
    }

    @Test
    fun `python forms`() {
        assertEquals(AppiumLocator(AppiumBy.ID, "x"), AppiumLocatorQuery.parse("(AppiumBy.ID, \"x\")"))
        assertEquals(AppiumLocator(AppiumBy.ACCESSIBILITY_ID, "x"), AppiumLocatorQuery.parse("driver.find_element(AppiumBy.ACCESSIBILITY_ID, 'x')"))
        assertEquals(AppiumLocator(AppiumBy.IOS_CLASS_CHAIN, "**/A"), AppiumLocatorQuery.parse("driver.find_elements(by=AppiumBy.IOS_CLASS_CHAIN, value=\"**/A\")"))
        assertEquals(AppiumLocator(AppiumBy.IOS_PREDICATE, "name == 'a'"), AppiumLocatorQuery.parse("(AppiumBy.IOS_PREDICATE, \"name == 'a'\")"))
        assertEquals(AppiumLocator(AppiumBy.XPATH, "//a\\b"), AppiumLocatorQuery.parse("(By.XPATH, r\"//a\\b\")"))
        assertEquals(AppiumLocator(AppiumBy.ANDROID_UIAUTOMATOR, "new UiSelector().text(\"a\")"), AppiumLocatorQuery.parse("(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text(\"a\")')"))
    }

    @Test
    fun `csharp forms`() {
        assertEquals(AppiumLocator(AppiumBy.ID, "x"), AppiumLocatorQuery.parse("By.Id(\"x\")"))
        assertEquals(AppiumLocator(AppiumBy.ACCESSIBILITY_ID, "x"), AppiumLocatorQuery.parse("driver.FindElement(MobileBy.AccessibilityId(\"x\"));"))
        assertEquals(AppiumBy.IOS_PREDICATE, AppiumLocatorQuery.parse("MobileBy.IosNSPredicate(\"name == 'a'\")").by)
        assertEquals(AppiumBy.IOS_CLASS_CHAIN, AppiumLocatorQuery.parse("MobileBy.IosClassChain(\"**/A\")").by)
        assertEquals(AppiumBy.ANDROID_UIAUTOMATOR, AppiumLocatorQuery.parse("MobileBy.AndroidUIAutomator(\"new UiSelector()\")").by)
        assertEquals(AppiumLocator(AppiumBy.XPATH, "//a[@b=\"c\"]"), AppiumLocatorQuery.parse("By.XPath(@\"//a[@b=\"\"c\"\"]\")"))
        assertEquals(AppiumBy.ACCESSIBILITY_ID, AppiumLocatorQuery.parse("AppiumBy.AccessibilityId(\"x\")").by)
    }

    @Test
    fun `detection keeps xpath and mobilewright apart`() {
        assertTrue(AppiumLocatorQuery.looksLikeAppium("(AppiumBy.ID, \"x\")"))
        assertTrue(!AppiumLocatorQuery.looksLikeAppium("(//a[@text=\"By.id(x)\"])[1]"))
        assertTrue(!AppiumLocatorQuery.looksLikeAppium("//a[@text='By.id']"))
        assertTrue(!AppiumLocatorQuery.looksLikeAppium("screen.getByText('By.id(\"x\")')"))
        assertTrue(!AppiumLocatorQuery.looksLikeAppium("screen.getByTestId('x')"))
        assertEquals(1, count("(//android.widget.Button)[1]", android))
    }

    @Test
    fun `refusals are precise`() {
        refused("By.name(\"x\")", android, "not supported yet: By.name")
        refused("By.cssSelector(\"x\")", android, "not supported yet")
        refused("By.id(\"a\" + b)", android, "not supported yet: + b")
        refused("driver.findElement(By.id(\"a\")).click()", android, "not supported yet: .click()")
        refused("driver.find_element(\"id\", \"x\")", android, "not supported yet")
        refused("By.id($\"x\")", android, "interpolated")
        refused("By.id(\"x\"", android, "")
        refused("id=", android, "needs a value")
    }

    // ---- id and accessibility id

    @Test
    fun `id and accessibility id on android`() {
        assertEquals(1, count("id=com.example.app:id/email", android))
        assertEquals(0, count("id=email", android))
        assertEquals(0, count("id=com.example.app:id/", android))
        assertEquals(1, count("accessibility id=Email", android))
        assertEquals(0, count("accessibility id=Sign In", android))
        assertEquals(1, count("By.id(\"com.example.app:id/email\")", android))
        assertEquals(2, count("By.className(\"android.widget.Button\")", android))
    }

    @Test
    fun `id and accessibility id on ios use name`() {
        assertEquals(1, count("id=email-input", ios))
        assertEquals(1, count("AppiumBy.accessibilityId(\"email-input\")", ios))
        assertEquals(0, count("accessibility id=Email", ios))
        assertEquals(1, count("accessibility id=Sign In", ios))
        assertEquals(2, count("class name=XCUIElementTypeButton", ios))
    }

    @Test
    fun `xpath code form`() {
        assertEquals(1, count("driver.findElement(By.xpath(\"//android.widget.EditText\"))", android))
        assertEquals(1, count("xpath=/hierarchy/android.widget.FrameLayout[1]/android.widget.LinearLayout[1]/android.widget.EditText[1]", android))
    }

    // ---- UiSelector

    @Test
    fun `uiselector text and description`() {
        fun ui(chain: String) = count("-android uiautomator=new UiSelector()$chain", android)
        assertEquals(2, ui(".text(\"Sign In\")"))
        assertEquals(2, ui(".textContains(\"Sign\")"))
        assertEquals(2, ui(".textStartsWith(\"Si\")"))
        assertEquals(2, ui(".textMatches(\"Sign.*\")"))
        assertEquals(0, ui(".textMatches(\"Sign\")"))
        assertEquals(1, ui(".description(\"Email\")"))
        assertEquals(1, ui(".descriptionContains(\"mail\")"))
        assertEquals(1, ui(".descriptionStartsWith(\"Em\")"))
        assertEquals(1, ui(".descriptionMatches(\"E.*l\")"))
        assertEquals(1, ui(".resourceId(\"com.example.app:id/email\")"))
        assertEquals(1, ui(".resourceIdMatches(\".*:id/em.*\")"))
        assertEquals(2, ui(".className(\"android.widget.Button\")"))
        assertEquals(2, ui(".classNameMatches(\".*Button\")"))
        assertEquals(6, ui(".packageName(\"com.example.app\")"))
    }

    @Test
    fun `uiselector flags index and instance`() {
        fun ui(chain: String) = LocatorQuery.find("-android uiautomator=new UiSelector()$chain", android)
        assertEquals(3, ui(".clickable(true)").size)
        assertEquals(1, ui(".clickable(true).className(\"android.widget.EditText\")").size)
        assertEquals(3, ui(".clickable(false)").size)
        assertEquals(3, ui(".focusable(true).clickable(true).enabled(true).selected(false).checked(false).focused(false)").size)
        val second = ui(".className(\"android.widget.Button\").instance(1)").single()
        assertEquals(android.walk().filter { it.rawType == "android.widget.Button" }.toList()[1], second)
        assertTrue(ui(".className(\"android.widget.Button\").instance(5)").isEmpty())
        assertEquals("Welcome", ui(".index(0)").first { it.rawType.endsWith("TextView") }.attr("text"))
        assertEquals(1, ui(".className(\"android.widget.Button\").index(3)").size)
    }

    @Test
    fun `uiselector child selector finds descendants`() {
        val found = LocatorQuery.find("-android uiautomator=new UiSelector().className(\"android.widget.LinearLayout\").childSelector(new UiSelector().text(\"Sign In\").instance(1))", android)
        assertEquals(1, found.size)
        assertSame(android.walk().filter { it.attr("text") == "Sign In" }.toList()[1], found.single())
        assertEquals(2, count("-android uiautomator=new UiSelector().className(\"android.widget.FrameLayout\").childSelector(new UiSelector().text(\"Sign In\"))", android))
        assertEquals(0, count("-android uiautomator=new UiSelector().text(\"Welcome\").childSelector(new UiSelector().text(\"x\"))", android))
    }

    @Test
    fun `uiselector refusals`() {
        fun ui(chain: String) = "-android uiautomator=new UiSelector()$chain"
        refused(ui(".fromParent(new UiSelector().text(\"a\"))"), android, "fromParent")
        refused(ui(".bogus(\"a\")"), android, "not supported yet: UiSelector.bogus")
        refused("-android uiautomator=new UiScrollable(new UiSelector().scrollable(true))", android, "UiScrollable")
        refused(ui(".text(\"a\") extra"), android, "not supported yet")
        refused(ui(".instance(x)"), android, "takes a number")
        refused(ui(".clickable(maybe)"), android, "true or false")
        refused(ui(".textMatches(\"[\")"), android, "Invalid regex")
        refused(ui(".text(\"a\")"), ios, "needs an Android screen")
    }

    @Test
    fun `uiselector refuses string methods UiSelector does not have`() {
        fun ui(chain: String) = "-android uiautomator=new UiSelector()$chain"
        for (m in listOf("resourceIdContains", "resourceIdStartsWith", "classNameContains", "classNameStartsWith", "packageNameContains", "packageNameStartsWith")) {
            refused(ui(".$m(\"a\")"), android, "not supported yet: UiSelector.$m")
        }
        assertEquals(1, count(ui(".resourceIdMatches(\".*email\")"), android))
    }

    @Test
    fun `attributes missing from the whole hierarchy are refused not counted`() {
        val a = UiNode(Platform.ANDROID, "hierarchy", emptyMap(), null)
        a.addChild(UiNode(Platform.ANDROID, "android.widget.Button", mapOf("text" to "x", "enabled" to "true"), null))
        refused("-android uiautomator=new UiSelector().scrollable(true)", a, "'scrollable' is not in this hierarchy dump")
        refused("-android uiautomator=new UiSelector().focusable(false)", a, "not in this hierarchy dump")
        refused("-android uiautomator=new UiSelector().packageName(\"p\")", a, "'package' is not in this hierarchy dump")
        refused("-android uiautomator=new UiSelector().text(\"x\").childSelector(new UiSelector().longClickable(true))", a, "'long-clickable'")
        assertEquals(1, count("-android uiautomator=new UiSelector().enabled(true)", a))

        val i = UiNode(Platform.IOS, "XCUIElementTypeApplication", emptyMap(), null)
        i.addChild(UiNode(Platform.IOS, "XCUIElementTypeButton", mapOf("name" to "x", "enabled" to "true", "visible" to "true"), null))
        refused("-ios predicate string=accessible == true", i, "'accessible' is not in this hierarchy dump")
        refused("-ios predicate string=name == 'x' AND NOT (accessible == false)", i, "not in this hierarchy dump")
        assertEquals(1, count("-ios predicate string=visible == true", i))
    }

    @Test
    fun `uiselector code form with escaped quotes`() {
        assertEquals(2, count("AppiumBy.androidUIAutomator(\"new UiSelector().text(\\\"Sign In\\\")\")", android))
        assertEquals(2, count("driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text(\"Sign In\")')", android))
    }

    // ---- iOS predicate

    private fun pred(p: String) = LocatorQuery.find("-ios predicate string=$p", ios)

    @Test
    fun `predicate comparisons`() {
        assertEquals(2, pred("type == \"XCUIElementTypeButton\"").size)
        assertEquals(1, pred("type == 'XCUIElementTypeButton' AND name == 'Sign In'").size)
        assertEquals(1, pred("name = 'Sign In' && visible == true && type = 'XCUIElementTypeButton'").size)
        assertEquals(3, pred("type == 'XCUIElementTypeButton' OR name == 'Welcome'").size)
        assertEquals(3, pred("NOT (type == 'XCUIElementTypeButton')").size)
        assertEquals(1, pred("label CONTAINS 'mai'").size)
        assertEquals(0, pred("label CONTAINS 'MAI'").size)
        assertEquals(1, pred("label CONTAINS[c] 'MAI'").size)
        assertEquals(1, pred("label BEGINSWITH 'Em'").size)
        assertEquals(1, pred("label ENDSWITH 'ail'").size)
        assertEquals(1, pred("name LIKE 'S*' AND type == 'XCUIElementTypeButton'").size)
        assertEquals(1, pred("name LIKE 'Welc?me'").size)
        assertEquals(1, pred("name MATCHES 'email-.*'").size)
        assertEquals(0, pred("name MATCHES 'email'").size)
        assertEquals(2, pred("name IN {'Welcome', 'email-input'}").size)
        assertEquals(1, pred("identifier == 'email-input'").size)
        assertEquals(3, pred("name != 'Welcome' AND type CONTAINS 'XCUIElementTypeB' OR type == 'XCUIElementTypeTextField'").size)
        assertEquals(1, pred("visible == false").size)
        assertEquals(1, pred("visible == 0").size)
        assertEquals(5, pred("enabled == YES").size)
        assertEquals(1, pred("(name == 'Hidden' OR name == 'Welcome') AND visible == true").size)
    }

    @Test
    fun `predicate not-equal includes elements without the attribute`() {
        // The window has no name: NSPredicate treats nil != 'x' as true.
        assertEquals(4, pred("name != 'Welcome'").size)
    }

    @Test
    fun `predicate refusals`() {
        refused("-ios predicate string=hittable == true", ios, "predicate field 'hittable'")
        refused("-ios predicate string=name > 'a'", ios, "operator >")
        refused("-ios predicate string=value == 5", ios, "not supported yet: 5")
        refused("-ios predicate string=enabled CONTAINS 'a'", ios, "boolean")
        refused("-ios predicate string=name == %@", ios, "'%'")
        refused("-ios predicate string=SUBQUERY(a, \$x, true)", ios, "not supported yet")
        refused("-ios predicate string=name == 'a' AND", ios, "")
        refused("-ios predicate string=(name == 'a'", ios, "closing parenthesis")
        refused("-ios predicate string=name MATCHES '['", ios, "Invalid regex")
        refused("-ios predicate string=name ==[x] 'a'", ios, "modifier")
        refused("-ios predicate string=name == 'a'", android, "needs an iOS screen")
    }

    // ---- class chain

    private fun chain(c: String) = LocatorQuery.find("-ios class chain=$c", ios)

    @Test
    fun `class chain forms`() {
        assertEquals(2, chain("**/XCUIElementTypeButton").size)
        assertEquals(1, chain("**/XCUIElementTypeButton[`name == \"Sign In\"`]").size)
        assertEquals(1, chain("**/XCUIElementTypeButton[`name == 'Sign In'`]").size)
        assertEquals(2, chain("XCUIElementTypeWindow/XCUIElementTypeButton").size)
        assertEquals(0, chain("XCUIElementTypeButton").size)
        assertEquals(4, chain("XCUIElementTypeWindow/*").size)
        assertEquals("Hidden", chain("**/XCUIElementTypeButton[2]").single().attr("name"))
        assertEquals("Hidden", chain("**/XCUIElementTypeButton[-1]").single().attr("name"))
        assertEquals("Sign In", chain("XCUIElementTypeWindow/XCUIElementTypeButton[1]").single().attr("name"))
        assertEquals("Sign In", chain("**/XCUIElementTypeButton[`visible == true`][1]").single().attr("name"))
        assertEquals(0, chain("**/XCUIElementTypeButton[3]").size)
        assertEquals(1, chain("**/XCUIElementTypeWindow/XCUIElementTypeTextField[`label == 'Email' AND name == 'email-input'`]").size)
        assertEquals(1, chain("**/XCUIElementTypeButton[`name == 'a/b' OR name == 'Hidden'`]").size)
        assertEquals(5, chain("**/*").size)
    }

    @Test
    fun `class chain refusals`() {
        refused("-ios class chain=**/XCUIElementTypeCell[\$name == 'x'\$]", ios, "not supported yet")
        refused("-ios class chain=**/XCUIElementTypeButton[0]", ios, "not supported yet")
        refused("-ios class chain=**", ios, "cannot end with **")
        refused("-ios class chain=**/Button", ios, "Unknown element type")
        refused("-ios class chain=**//XCUIElementTypeButton", ios, "Empty segment")
        refused("-ios class chain=**/XCUIElementTypeButton[`name == 'x'", ios, "Unbalanced backtick")
        refused("-ios class chain=**/XCUIElementTypeButton[1", ios, "Missing ]")
        refused("-ios class chain=**/XCUIElementTypeButton", android, "needs an iOS screen")
    }

    // ---- consistency with the generator

    @Test
    fun `every appium locator the generator shows finds exactly its own element`() {
        val languages = listOf(PageObjectLanguage.APPIUM_JAVA, PageObjectLanguage.APPIUM_KOTLIN, PageObjectLanguage.APPIUM_PYTHON, PageObjectLanguage.APPIUM_CSHARP)
        var checked = 0
        for (root in listOf(android, ios)) {
            for (node in root.walk().filter { !it.isRoot && it.attributes.isNotEmpty() }) {
                val suggestions = LocatorGenerator.suggest(node, root).filter { it.framework == "Appium" }
                for (s in suggestions) {
                    // The bare form, as shown in the table.
                    val bare = "${AppiumBy.entries.first { it.name == AppiumStrategy.fromLocatorName(s.strategy).name }.label}=${s.expression}"
                    val found = LocatorQuery.find(bare, root)
                    assertEquals("$bare matchCount", s.matchCount, found.size)
                    if (s.isUnique) assertSame(bare, node, found.single())
                    // The code forms the Copy as combo writes.
                    val strategy = AppiumStrategy.fromLocatorName(s.strategy)
                    for (language in languages) {
                        val code = PageObjectGenerator.appiumCode(language, PageLocator.Appium(strategy, s.expression)) ?: continue
                        val viaCode = LocatorQuery.find("driver.findElement($code)", root)
                        assertEquals("$code matchCount", found, viaCode)
                        assertEquals(found, LocatorQuery.find(code, root))
                    }
                    checked++
                }
            }
        }
        assertTrue("checked $checked", checked > 30)
    }
}
