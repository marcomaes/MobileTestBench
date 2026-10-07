package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.memory.MemoryCommands
import io.github.marcomaes.mobiletestbench.core.memory.MemoryParsers
import io.github.marcomaes.mobiletestbench.core.memory.TrimLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTest {
    @Test fun buildsCommand() {
        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "am", "send-trim-memory", "com.example.app", "RUNNING_CRITICAL"),
            MemoryCommands.trim("adb", "emulator-5554", "com.example.app", TrimLevel.RUNNING_CRITICAL),
        )
    }

    @Test fun levelListIsCompleteAndOrdered() {
        assertEquals(
            listOf("HIDDEN", "RUNNING_MODERATE", "BACKGROUND", "RUNNING_LOW", "MODERATE", "RUNNING_CRITICAL", "COMPLETE"),
            TrimLevel.values().map { it.arg },
        )
    }

    @Test fun validatesProcessNames() {
        assertTrue(MemoryCommands.validProcess("com.example.app"))
        assertTrue(MemoryCommands.validProcess("com.example.app:sync"))
        assertFalse(MemoryCommands.validProcess(""))
        assertFalse(MemoryCommands.validProcess("com.example; rm -rf /"))
        assertFalse(MemoryCommands.validProcess("a b"))
    }

    @Test fun findsErrors() {
        assertNull(MemoryParsers.error(""))
        assertNotNull(MemoryParsers.error("Unknown error: process com.x not found"))
        assertNotNull(MemoryParsers.error("Error: Unknown level: FOO"))
        assertNotNull(MemoryParsers.error("java.lang.SecurityException: denied"))
        assertEquals("boom", MemoryParsers.errorLine("", "boom\nmore", 1))
        assertEquals("Exit code 3", MemoryParsers.errorLine("", "", 3))
    }
}
