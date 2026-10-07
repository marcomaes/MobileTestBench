package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.LocatorSuggestion
import io.github.marcomaes.mobiletestbench.core.pageobject.AppiumStrategy
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorCode
import io.github.marcomaes.mobiletestbench.core.pageobject.LocatorLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocatorCodeTest {

    private fun appium(strategy: String, value: String) = LocatorSuggestion("Appium", strategy, value, 1)
    private val mw = LocatorSuggestion("Mobilewright", "testId", "screen.getByTestId('login')", 1)

    private val rows = listOf(
        appium("id", "com.example:id/email"),
        appium("accessibility id", "Log \"in\""),
        appium("-android uiautomator", "new UiSelector().text(\"\$x\")"),
        appium("-ios predicate string", "name == 'a'"),
        appium("-ios class chain", "**/XCUIElementTypeButton[`name == 'a'`]"),
        appium("xpath", "//Button[@text=\"a\"]"),
        appium("xpath (in parent)", "//Card//Button"),
    )

    private fun all(language: LocatorLanguage) = rows.map { LocatorCode.render(it, language) }

    @Test
    fun `java`() = assertEquals(
        listOf(
            "By.id(\"com.example:id/email\")",
            "AppiumBy.accessibilityId(\"Log \\\"in\\\"\")",
            "AppiumBy.androidUIAutomator(\"new UiSelector().text(\\\"\$x\\\")\")",
            "AppiumBy.iOSNsPredicateString(\"name == 'a'\")",
            "AppiumBy.iOSClassChain(\"**/XCUIElementTypeButton[`name == 'a'`]\")",
            "By.xpath(\"//Button[@text=\\\"a\\\"]\")",
            "By.xpath(\"//Card//Button\")",
        ),
        all(LocatorLanguage.APPIUM_JAVA),
    )

    @Test
    fun `kotlin escapes dollar`() {
        val out = all(LocatorLanguage.APPIUM_KOTLIN)
        assertEquals("AppiumBy.androidUIAutomator(\"new UiSelector().text(\\\"\\\$x\\\")\")", out[2])
        assertEquals("By.id(\"com.example:id/email\")", out[0])
    }

    @Test
    fun `python tuples`() {
        val out = all(LocatorLanguage.APPIUM_PYTHON)
        assertEquals("(AppiumBy.ID, \"com.example:id/email\")", out[0])
        assertEquals("(AppiumBy.ACCESSIBILITY_ID, \"Log \\\"in\\\"\")", out[1])
        assertEquals("(AppiumBy.ANDROID_UIAUTOMATOR, \"new UiSelector().text(\\\"\$x\\\")\")", out[2])
        assertEquals("(AppiumBy.IOS_PREDICATE, \"name == 'a'\")", out[3])
        assertEquals("(AppiumBy.IOS_CLASS_CHAIN, \"**/XCUIElementTypeButton[`name == 'a'`]\")", out[4])
        assertEquals("(AppiumBy.XPATH, \"//Button[@text=\\\"a\\\"]\")", out[5])
        assertEquals("(AppiumBy.XPATH, \"//Card//Button\")", out[6])
    }

    @Test
    fun `csharp uses MobileBy`() {
        val out = all(LocatorLanguage.APPIUM_CSHARP)
        assertEquals("By.Id(\"com.example:id/email\")", out[0])
        assertEquals("MobileBy.AccessibilityId(\"Log \\\"in\\\"\")", out[1])
        assertEquals("MobileBy.AndroidUIAutomator(\"new UiSelector().text(\\\"\$x\\\")\")", out[2])
        assertEquals("MobileBy.IosNSPredicate(\"name == 'a'\")", out[3])
        assertEquals("MobileBy.IosClassChain(\"**/XCUIElementTypeButton[`name == 'a'`]\")", out[4])
        assertEquals("By.XPath(\"//Button[@text=\\\"a\\\"]\")", out[5])
    }

    private fun line(row: Int, l: LocatorLanguage) = LocatorCode.render(rows[row], l, wholeLine = true)

    @Test
    fun `whole line for every language`() {
        assertEquals("driver.findElement(By.id(\"com.example:id/email\"))", line(0, LocatorLanguage.APPIUM_JAVA))
        assertEquals("driver.findElement(AppiumBy.accessibilityId(\"Log \\\"in\\\"\"))", line(1, LocatorLanguage.APPIUM_KOTLIN))
        assertEquals("driver.find_element(AppiumBy.ID, \"com.example:id/email\")", line(0, LocatorLanguage.APPIUM_PYTHON))
        assertEquals("driver.find_element(AppiumBy.XPATH, \"//Card//Button\")", line(6, LocatorLanguage.APPIUM_PYTHON))
        assertEquals("driver.FindElement(MobileBy.AccessibilityId(\"Log \\\"in\\\"\"))", line(1, LocatorLanguage.APPIUM_CSHARP))
        assertEquals("await \$('id=com.example:id/email')", line(0, LocatorLanguage.APPIUM_WEBDRIVERIO))
        assertEquals("screen.getByTestId('login')", LocatorCode.render(mw, LocatorLanguage.MOBILEWRIGHT, wholeLine = true))
        assertEquals("com.example:id/email", line(0, LocatorLanguage.AS_SHOWN))
    }

    @Test
    fun `whole line keeps missing forms missing and textFor passes it on`() {
        assertNull(LocatorCode.render(mw, LocatorLanguage.APPIUM_JAVA, wholeLine = true))
        assertEquals("driver.findElement(By.id(\"com.example:id/email\"))", LocatorCode.textFor(rows[0], LocatorLanguage.APPIUM_JAVA, true).text)
        assertEquals("By.id(\"com.example:id/email\")", LocatorCode.textFor(rows[0], LocatorLanguage.APPIUM_JAVA).text)
    }

    @Test
    fun `as shown and mobilewright keep the expression`() {
        assertEquals("screen.getByTestId('login')", LocatorCode.render(mw, LocatorLanguage.AS_SHOWN))
        assertEquals("screen.getByTestId('login')", LocatorCode.render(mw, LocatorLanguage.MOBILEWRIGHT))
        assertEquals("com.example:id/email", LocatorCode.render(rows[0], LocatorLanguage.AS_SHOWN))
    }

    @Test
    fun `rows without a form in the language give null and fall back as shown with a note`() {
        for (l in listOf(LocatorLanguage.APPIUM_JAVA, LocatorLanguage.APPIUM_KOTLIN, LocatorLanguage.APPIUM_PYTHON, LocatorLanguage.APPIUM_CSHARP)) {
            assertNull(LocatorCode.render(mw, l))
        }
        assertNull(LocatorCode.render(rows[0], LocatorLanguage.MOBILEWRIGHT))
        val t = LocatorCode.textFor(mw, LocatorLanguage.APPIUM_JAVA)
        assertEquals("screen.getByTestId('login')", t.text)
        assertEquals("No Appium Java form for a Mobilewright locator, used as shown", t.note)
        assertNotNull(LocatorCode.textFor(rows[0], LocatorLanguage.MOBILEWRIGHT).note)
        assertNull(LocatorCode.textFor(rows[0], LocatorLanguage.APPIUM_JAVA).note)
    }

    @Test
    fun `menu labels name the language only when there is a form`() {
        assertEquals("Copy Appium id", LocatorCode.menuLabel(rows[0], LocatorLanguage.AS_SHOWN))
        assertEquals("Copy Appium Java id", LocatorCode.menuLabel(rows[0], LocatorLanguage.APPIUM_JAVA))
        assertEquals("Copy Mobilewright testId", LocatorCode.menuLabel(mw, LocatorLanguage.MOBILEWRIGHT))
        assertEquals("Copy Mobilewright testId", LocatorCode.menuLabel(mw, LocatorLanguage.APPIUM_JAVA))
    }

    @Test
    fun `strategy names map in one place and unknown names are xpath`() {
        assertEquals(AppiumStrategy.ACCESSIBILITY_ID, AppiumStrategy.fromLocatorName("accessibility id"))
        assertEquals(AppiumStrategy.XPATH, AppiumStrategy.fromLocatorName("xpath (in parent)"))
        assertEquals(AppiumStrategy.XPATH, AppiumStrategy.fromLocatorName("xpath (absolute)"))
        assertEquals(LocatorLanguage.AS_SHOWN, LocatorLanguage.fromName("nonsense"))
        assertEquals(LocatorLanguage.APPIUM_PYTHON, LocatorLanguage.fromName("APPIUM_PYTHON"))
    }
}
