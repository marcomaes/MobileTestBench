package io.github.marcomaes.mobiletestbench.core

import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.apps.Apps
import io.github.marcomaes.mobiletestbench.core.apps.ForegroundApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsTest {
    private val android = Device("emulator-5554", "Pixel", Platform.ANDROID, DeviceKind.EMULATOR)
    private val sim = Device("F236", "iPhone", Platform.IOS, DeviceKind.SIMULATOR)
    private val phone = Device("0000-1", "iPhone", Platform.IOS, DeviceKind.PHYSICAL)

    private fun fixture(name: String) =
        MobilecliJson.data(javaClass.getResource("/fixtures/$name")!!.readText())

    @Test
    fun `android fixture parses and sorts by name`() {
        val apps = Apps.parseList(fixture("mobilecli-apps-list-android.json"))
        assertEquals(24, apps.size)
        assertEquals(apps.map { it.name.lowercase() }.sorted(), apps.map { it.name.lowercase() })
        assertTrue(apps.any { it.id == "com.android.settings" && it.name == "Settings" })
    }

    @Test
    fun `ios fixture parses`() {
        val apps = Apps.parseList(fixture("mobilecli-apps-list-ios.json"))
        assertEquals(40, apps.size)
        assertTrue(apps.any { it.id == "com.apple.Preferences" })
    }

    @Test
    fun `list tolerates junk`() {
        assertTrue(Apps.parseList(null).isEmpty())
        val data = JsonParser.parseString("""[{"appName":"x"},{"packageName":"a.b"},5]""")
        val apps = Apps.parseList(data)
        assertEquals(1, apps.size)
        assertEquals("a.b", apps[0].name)
    }

    @Test
    fun `foreground with and without activity`() {
        val a = Apps.parseForeground(JsonParser.parseString("""{"packageName":"com.x","appName":"X","version":"1","activity":"com.x.Main"}"""))!!
        assertEquals("com.x.Main", a.activity)
        val home = Apps.parseForeground(JsonParser.parseString("""{"packageName":"com.apple.springboard","appName":"SpringBoard"}"""))!!
        assertTrue(home.isHome)
        assertNull(home.activity)
        assertNull(Apps.parseForeground(null))
        assertTrue(!ForegroundApp("a", "a").isHome)
    }

    @Test
    fun `filter matches name or id`() {
        val apps = Apps.parseList(fixture("mobilecli-apps-list-android.json"))
        assertEquals(apps.size, Apps.filter(apps, "  ").size)
        assertNotNull(Apps.filter(apps, "SETTINGS").firstOrNull { it.id == "com.android.settings" })
        assertTrue(Apps.filter(apps, "zzzzqq").isEmpty())
    }

    @Test
    fun `launch args`() {
        assertEquals(listOf("apps", "launch", "a.b"), Apps.launchArgs(android, "a.b"))
        assertEquals(listOf("apps", "launch", "a.b", "--locale", "fr-FR,en-GB", "--activity", ".Main"), Apps.launchArgs(android, "a.b", "fr-FR, en-GB", " .Main "))
        assertEquals(listOf("apps", "launch", "a.b", "--locale", "nl-NL"), Apps.launchArgs(sim, "a.b", "nl-NL", ".Main"))
    }

    @Test
    fun `clear support`() {
        assertTrue(Apps.canClearData(android))
        assertTrue(Apps.canClearData(sim))
        assertTrue(!Apps.canClearData(phone))
    }

    @Test
    fun `install validation`() {
        assertNull(Apps.installProblem(android, "/x/app.APK"))
        assertNotNull(Apps.installProblem(android, "/x/app.zip"))
        assertNull(Apps.installProblem(sim, "/x/App.zip"))
        assertNotNull(Apps.installProblem(sim, "/x/app.ipa"))
        assertNull(Apps.installProblem(phone, "/x/app.ipa"))
        assertNotNull(Apps.installProblem(phone, "/x/app.zip"))
        assertNotNull(Apps.installProblem(android, "/x/readme.txt"))
    }

    @Test
    fun `errors`() {
        assertTrue(Apps.friendlyError("failed: exit status 3").contains("not installed"))
        assertEquals("boom", Apps.friendlyError("boom"))
    }

    private val settingsApp = io.github.marcomaes.mobiletestbench.core.apps.AppInfo("com.android.settings", "Settings")
    private val mine = io.github.marcomaes.mobiletestbench.core.apps.AppInfo("com.mine.app", "Mine")

    @Test
    fun `user packages parse pm output`() {
        val out = "package:com.mine.app\r\npackage:/data/app/x/base.apk=com.other\nwarning\npackage:\n"
        assertEquals(setOf("com.mine.app", "com.other"), Apps.parseUserPackages(out))
        assertTrue(Apps.parseUserPackages("").isEmpty())
    }

    @Test
    fun `android system means not user installed`() {
        val user = setOf("com.mine.app")
        assertTrue(Apps.isSystem(android, settingsApp, user))
        assertEquals(false, Apps.isSystem(android, mine, user))
        assertEquals(false, Apps.isSystem(android, settingsApp, null))
    }

    @Test
    fun `ios system means com apple prefix`() {
        val apple = io.github.marcomaes.mobiletestbench.core.apps.AppInfo("com.apple.Preferences", "Settings")
        assertTrue(Apps.isSystem(sim, apple, null))
        assertTrue(Apps.isSystem(phone, apple, null))
        assertEquals(false, Apps.isSystem(sim, io.github.marcomaes.mobiletestbench.core.apps.AppInfo("com.applesauce.x", "X"), null))
    }

    @Test
    fun `hideSystem filters keeps the selected one and can be off`() {
        val apps = listOf(settingsApp, mine)
        val user = setOf("com.mine.app")
        assertEquals(listOf(mine), Apps.hideSystem(apps, android, user, true))
        assertEquals(apps, Apps.hideSystem(apps, android, user, false))
        assertEquals(apps, Apps.hideSystem(apps, android, user, true, keepId = "com.android.settings"))
        assertEquals(apps, Apps.hideSystem(apps, android, null, true))
    }

    @Test
    fun `ios fixture hides apple apps`() {
        val apps = Apps.parseList(fixture("mobilecli-apps-list-ios.json"))
        val shown = Apps.hideSystem(apps, sim, null, true)
        assertTrue(shown.size < apps.size)
        assertTrue(shown.none { it.id.startsWith("com.apple.") })
    }
}
