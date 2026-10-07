package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.ios.AgentInstallHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInstallHintTest {

    private val raw = "Screenshot failed: Exit code 1: /bin/mobilecli screenshot --device 0000-1 -o - failed to start agent on device 0000-1: " +
        "agent is not installed, use 'mobilecli agent install --device 0000-1 --provisioning-profile  ' to install it"

    @Test
    fun missingAgentBecomesFullSetupHint() {
        val hint = AgentInstallHint.rewrite(raw, "0000-1")
        assertTrue(hint.contains("mobilecli agent install --device 0000-1 --provisioning-profile <file.mobileprovision>"))
        assertTrue(hint.contains("Developer Mode"))
        assertTrue(hint.contains("UDID"))
        assertTrue(hint.contains("VPN & Device Management"))
    }

    @Test
    fun otherErrorsPassThrough() {
        assertEquals("Screenshot failed: boom", AgentInstallHint.rewrite("Screenshot failed: boom", "0000-1"))
    }
}
