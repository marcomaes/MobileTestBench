package io.github.marcomaes.mobiletestbench

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.marcomaes.mobiletestbench.core.Device
import io.github.marcomaes.mobiletestbench.core.DeviceKind
import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.exec.ToolLocator
import io.github.marcomaes.mobiletestbench.core.files.FilesException
import io.github.marcomaes.mobiletestbench.core.input.InputActions

/** A machine without mobilecli: every feature that needs it must fail with a message that says so, not an exception from deep inside. */
class DeviceServicesWithoutMobilecliTest : BasePlatformTestCase() {

    private val android = Device("R3GL701H6WK", "Phone", Platform.ANDROID, DeviceKind.PHYSICAL)
    private val iphone = Device("00008110-000C28D91107801E", "iPhone", Platform.IOS, DeviceKind.PHYSICAL)
    private val simulator = Device("F2368465", "iPhone 17 Pro", Platform.IOS, DeviceKind.SIMULATOR)

    override fun setUp() {
        super.setUp()
        DeviceServices.toolsOverride = ToolLocator(emptyMap(), "Mac OS X", "/nonexistent-home", fallbackDirs = emptyList())
    }

    override fun tearDown() {
        try {
            DeviceServices.toolsOverride = null
        } finally {
            super.tearDown()
        }
    }

    private inline fun <reified T : Exception> messageOf(block: () -> Unit): String =
        try {
            block()
            fail("expected ${T::class.simpleName}")
            ""
        } catch (e: Exception) {
            assertTrue("unexpected ${e.javaClass}: ${e.message}", e is T)
            e.message.orEmpty()
        }

    fun testMobilecliIsNotFound() {
        assertNull(DeviceServices.mobilecli())
    }

    fun testGenericMobilecliCallSaysHowToInstall() {
        val message = messageOf<IllegalStateException> { DeviceServices.mobilecliCall(android, "device", "info") }
        assertTrue(message, message.contains("mobilecli is required") && message.contains("npm install -g mobilecli"))
    }

    fun testScreenRecordingNeedsMobilecli() {
        val message = messageOf<IllegalStateException> { DeviceServices.startScreenRecording(android, java.io.File("unused.mp4")) }
        assertTrue(message, message.contains("mobilecli is required for screen recording"))
    }

    fun testPhysicalIphoneNeedsMobilecli() {
        val message = messageOf<IllegalStateException> { DeviceServices.createSession(iphone) }
        assertTrue(message, message.contains("mobilecli is required for physical iOS devices"))
    }

    fun testSimulatorFilesNeedMobilecli() {
        val message = messageOf<FilesException> { DeviceServices.files(simulator, null) }
        assertTrue(message, message.contains("need mobilecli"))
    }

    fun testPhysicalIphoneHasNoLogSource() {
        assertNull(DeviceServices.startLog(iphone, {}, {}))
    }

    fun testInputClipboardSaysItNeedsMobilecli() {
        val input = DeviceServices.input(android)
        assertFalse(input.hasMobilecli)
        val message = messageOf<IllegalStateException> { input.clipboardGet() }
        assertEquals(InputActions.CLIPBOARD_NEEDS_MOBILECLI, message)
    }
}
