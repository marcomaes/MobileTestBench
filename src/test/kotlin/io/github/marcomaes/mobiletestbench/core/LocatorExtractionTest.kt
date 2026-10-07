package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorExtraction
import io.github.marcomaes.mobiletestbench.core.inspector.ShowLocator
import io.github.marcomaes.mobiletestbench.core.inspector.ShowLocatorResult
import io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocatorExtractionTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)
    private val android: UiNode = UiAutomatorParser.parse(fixture("android_login.xml"))
    private val ios: UiNode = WdaSourceParser.parse(fixture("ios_login.xml"))

    private fun ex(text: String) = LocatorExtraction.extract(text)?.expression

    @Test fun mobilewrightLineWithAction() {
        assertEquals("screen.getByTestId(\"a\")", ex("""  await screen.getByTestId("a").tap();"""))
        assertEquals("screen.getByText('Sign In')", ex("await device.screen.getByText('Sign In').tap()").also { })
    }

    @Test fun mobilewrightChainAndNth() {
        assertEquals("screen.getByTestId('card').getByText('Buy').nth(1)", ex("await screen.getByTestId('card').getByText('Buy').nth(1).tap()"))
        assertEquals("screen.getByLabel(\"x\").first()", ex("""await expect(screen.getByLabel("x").first()).toBeVisible()"""))
    }

    @Test fun mobilewrightWithoutScreenPrefix() {
        assertEquals("screen.getByRole('button', { name: 'Go (now)' })", ex("""await getByRole('button', { name: 'Go (now)' }).tap()"""))
    }

    @Test fun javaAndKotlin() {
        assertEquals("findElement(By.id(\"x\"))", ex("""driver.findElement(By.id("x")).click();"""))
        assertEquals("By.id(\"x\")", ex("""val by = By.id("x")"""))
        assertEquals("AppiumBy.accessibilityId(\"y\")", ex("""driver.findElement(AppiumBy.accessibilityId("y")).sendKeys("a (b)")""").let { it!!.substringAfter("findElement(").removeSuffix(")") })
        assertEquals("""By.xpath("//a[@x='1']")""", ex("""WebElement e = driver.findElement(By.xpath("//a[@x='1']"));""")!!.substringAfter("findElement(").removeSuffix(")"))
    }

    @Test fun python() {
        assertEquals("""self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, "y")""".substringAfter("self.driver."), ex("""el = self.driver.find_element(AppiumBy.ACCESSIBILITY_ID, "y").click()"""))
        assertEquals("""find_element(by=AppiumBy.ID, value="x")""", ex("""driver.find_element(by=AppiumBy.ID, value="x")"""))
        assertEquals("""AppiumBy.ID, "x"""", ex("""LOGIN = (AppiumBy.ID, "x")"""))
        assertEquals("""xpath=//a[@b="c"]""", ex("""driver.find_element("xpath", '//a[@b="c"]').tap()"""))
    }

    @Test fun csharp() {
        assertEquals("""MobileBy.AccessibilityId("y")""", ex("""driver.FindElement(MobileBy.AccessibilityId("y")).Click();""")!!.substringAfter("FindElement(").removeSuffix(")"))
    }

    @Test fun prefixedStrings() {
        assertEquals("id=com.x:id/y", ex("""locator = "id=com.x:id/y""""))
        assertEquals("""xpath=//Button[@name="b"]""", ex("""const l = 'xpath=//Button[@name="b"]';""").let { it })
        assertEquals("accessibility id=Login", ex("""val s = "accessibility id=Login""""))
        assertEquals("id=abc", ex("""foo(id="abc")"""))
    }

    @Test fun quotedXPathAndBareSelection() {
        assertEquals("//XCUIElementTypeButton[@name='x']", ex("""val xp = "//XCUIElementTypeButton[@name='x']""""))
        assertEquals("(//a)[2]", ex("""x = '(//a)[2]'"""))
        assertEquals("//a[@b='c']", ex("//a[@b='c']"))
    }

    @Test fun multilineSelection() {
        assertEquals("screen.getByTestId('a').getByText('b')", ex("screen\n  .getByTestId('a')\n  .getByText('b')\n  .tap()").let { it })
    }

    @Test fun nothingFound() {
        assertNull(ex(""))
        assertNull(ex("   "))
        assertNull(ex("val x = 5 + 3"))
        assertNull(ex("""println("hello world")"""))
    }

    @Test fun textInsideMobilewrightStringDoesNotTriggerAppium() {
        assertEquals("screen.getByText(\"By.id\")", ex("""await screen.getByText("By.id").tap()"""))
    }

    // ---- evaluation

    @Test fun evaluatesAndNames() {
        val r = ShowLocator.evaluate("""await screen.getByTestId("email-input").tap()""", ios)
        assertTrue(r is ShowLocatorResult.Matches)
        assertEquals("1 match (unique): screen.getByTestId(\"email-input\")", r.message())
        assertTrue(r.isSuccess)
    }

    @Test fun noMatchAndSeveral() {
        assertTrue(ShowLocator.evaluate("""screen.getByTestId("nope-nope").tap()""", ios).message().startsWith("no match"))
        val many = ShowLocator.evaluate("""driver.findElements(By.xpath("//*"))""", android)
        assertTrue(many.message(), many.message().contains(" matches: "))
    }

    @Test fun androidIdFromJava() {
        val r = ShowLocator.evaluate("""driver.findElement(By.id("com.example.app:id/email")).click();""", android)
        assertEquals(1, (r as ShowLocatorResult.Matches).nodes.size)
    }

    @Test fun notSupportedAndNoLocator() {
        val r = ShowLocator.evaluate("""driver.findElement(By.name("x")).click()""", android)
        assertTrue(r.message(), r.message().startsWith("not supported: "))
        assertEquals("no locator found in the selection or on this line", ShowLocator.evaluate("val a = 1", android).message())
        assertEquals("no device connected: connect one in Mobile Devices", ShowLocatorResult.NoDevice.message())
    }
}
