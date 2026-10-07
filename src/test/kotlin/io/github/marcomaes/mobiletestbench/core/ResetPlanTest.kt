package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.apps.ResetChoices
import io.github.marcomaes.mobiletestbench.core.apps.ResetPlan
import io.github.marcomaes.mobiletestbench.core.apps.ResetStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetPlanTest {
    private val android = Device("emulator-5554", "Pixel", Platform.ANDROID, DeviceKind.EMULATOR)
    private val sim = Device("F236", "iPhone", Platform.IOS, DeviceKind.SIMULATOR)
    private val phone = Device("0000-1", "iPhone", Platform.IOS, DeviceKind.PHYSICAL)
    private val all = listOf(ResetStep.TERMINATE, ResetStep.CLEAR_DATA, ResetStep.RESET_PERMISSIONS, ResetStep.RELAUNCH)

    @Test
    fun `android and simulator keep all four steps in order`() {
        for (d in listOf(android, sim)) {
            val plan = ResetPlan.plan(d, ResetChoices())
            assertEquals(all, plan.map { it.step })
            assertTrue(plan.all { it.applies && it.note == null })
        }
    }

    @Test
    fun `unticked steps are removed`() {
        val plan = ResetPlan.plan(android, ResetChoices(terminate = false, clear = true, permissions = false, relaunch = true))
        assertEquals(listOf(ResetStep.CLEAR_DATA, ResetStep.RELAUNCH), plan.map { it.step })
        assertTrue(ResetPlan.plan(android, ResetChoices(false, false, false, false)).isEmpty())
    }

    @Test
    fun `physical iPhone skips clear and permissions with notes`() {
        val plan = ResetPlan.plan(phone, ResetChoices())
        assertEquals(all, plan.map { it.step })
        assertTrue(plan.first { it.step == ResetStep.TERMINATE }.applies)
        assertTrue(plan.first { it.step == ResetStep.RELAUNCH }.applies)
        for (s in listOf(ResetStep.CLEAR_DATA, ResetStep.RESET_PERMISSIONS)) {
            val p = plan.first { it.step == s }
            assertEquals(false, p.applies)
            assertNotNull(p.note)
        }
        assertNull(plan.first { it.step == ResetStep.TERMINATE }.note)
    }

    @Test
    fun `oneLine keeps the first non blank line`() {
        assertEquals("failed to clear: exit status 1", ResetPlan.oneLine("\nfailed to clear: exit status 1\nOutput: Failed\n"))
        assertEquals("failed", ResetPlan.oneLine("  "))
    }
}
