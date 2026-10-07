package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.freeze.FreezeState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class FreezeStateTest {
    @Test
    fun startsUnfrozen() {
        val s = FreezeState()
        assertFalse(s.frozen)
        assertFalse(s.isCurrent(0))
    }

    @Test
    fun freezeTokenIsCurrentUntilUnfrozen() {
        val s = FreezeState()
        val token = s.freeze()
        assertTrue(s.frozen)
        assertTrue(s.isCurrent(token))
        s.unfreeze()
        assertFalse(s.frozen)
        assertFalse(s.isCurrent(token))
    }

    @Test
    fun freezingTwiceKeepsTheToken() {
        val s = FreezeState()
        assertEquals(s.freeze(), s.freeze())
    }

    @Test
    fun oldTokenIsNotCurrentAfterRefreeze() {
        val s = FreezeState()
        val first = s.freeze()
        s.unfreeze()
        val second = s.freeze()
        assertFalse(s.isCurrent(first))
        assertTrue(s.isCurrent(second))
    }
}
