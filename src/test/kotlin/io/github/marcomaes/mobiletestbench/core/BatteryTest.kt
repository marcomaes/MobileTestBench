package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.battery.BatteryChange
import io.github.marcomaes.mobiletestbench.core.battery.BatteryCommands
import io.github.marcomaes.mobiletestbench.core.battery.BatteryParsers
import io.github.marcomaes.mobiletestbench.core.battery.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {
    private val dump = """
        Current Battery Service state:
          AC powered: false
          USB powered: true
          Wireless powered: false
          Max charging current: 500000
          status: 2
          health: 2
          present: true
          level: 42
          scale: 100
          voltage: 5000
          temperature: 250
          technology: Li-ion
    """.trimIndent()

    @Test fun parsesState() {
        val s = BatteryParsers.state(dump)!!
        assertEquals(42, s.level)
        assertEquals(BatteryStatus.CHARGING, s.status)
        assertEquals(false, s.ac)
        assertEquals(true, s.usb)
        assertFalse(s.updatesStopped)
    }

    @Test fun detectsUpdatesStopped() {
        assertTrue(BatteryParsers.state("Current Battery Service state:\n  UPDATES STOPPED\n  status: 3\n  level: 5")!!.updatesStopped)
    }

    @Test fun garbageIsNull() {
        assertNull(BatteryParsers.state("nothing here"))
    }

    @Test fun buildsCommands() {
        fun set(c: BatteryChange) = BatteryCommands.set("adb", "emulator-5554", c).drop(3)
        assertEquals(listOf("shell", "dumpsys", "battery", "set", "level", "15"), set(BatteryChange.Level(15)))
        assertEquals(listOf("shell", "dumpsys", "battery", "set", "status", "5"), set(BatteryChange.Status(BatteryStatus.FULL)))
        assertEquals(listOf("shell", "dumpsys", "battery", "set", "ac", "0"), set(BatteryChange.Ac(false)))
        assertEquals(listOf("shell", "dumpsys", "battery", "set", "usb", "1"), set(BatteryChange.Usb(true)))
        assertEquals("reset", BatteryCommands.reset("adb", "x").last())
    }

    @Test fun validatesLevel() {
        assertTrue(BatteryCommands.validLevel(0) && BatteryCommands.validLevel(100))
        assertFalse(BatteryCommands.validLevel(101) || BatteryCommands.validLevel(-1))
    }

    @Test fun findsErrors() {
        assertNotNull(BatteryParsers.error("Unknown set option: foo"))
        assertNull(BatteryParsers.error(""))
        assertEquals("boom", BatteryParsers.errorLine("", "boom\nmore", 1))
    }
}
