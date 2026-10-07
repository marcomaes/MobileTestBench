package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.recording.HierarchySnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HierarchySnapshotsTest {

    private fun node(label: String) = UiNode(Platform.IOS, "XCUIElementTypeApplication", mapOf("label" to label), null)

    @Test
    fun `a snapshot is ready without waiting for the tap`() {
        val calls = AtomicInteger()
        HierarchySnapshots({ node("v${calls.incrementAndGet()}") }, settleMs = 10, idleMs = 1000).use { snapshots ->
            snapshots.start()
            Thread.sleep(150)
            val started = System.nanoTime()
            val root = snapshots.awaitFresh(2000, 2000).root
            assertTrue((System.nanoTime() - started) / 1_000_000 < 50)
            assertEquals("v1", root.attr("label"))
        }
    }

    @Test
    fun `after an input only a newer snapshot is returned`() {
        val screen = AtomicInteger(1)
        HierarchySnapshots({ node("screen${screen.get()}") }, settleMs = 30, idleMs = 1000).use { snapshots ->
            snapshots.start()
            assertEquals("screen1", snapshots.awaitFresh(2000, 2000).root.attr("label"))
            screen.set(2) // the tap changed the screen
            snapshots.inputDone()
            assertEquals("screen2", snapshots.awaitFresh(2000, 2000).root.attr("label"))
        }
    }

    @Test
    fun `the screen is captured again while idle`() {
        val calls = AtomicInteger()
        HierarchySnapshots({ node("v${calls.incrementAndGet()}") }, settleMs = 5, idleMs = 40).use { snapshots ->
            snapshots.start()
            Thread.sleep(300)
            assertTrue(calls.get() >= 3)
        }
    }

    @Test
    fun `a failing capture is retried and its error reported on timeout`() {
        val calls = AtomicInteger()
        HierarchySnapshots({
            if (calls.incrementAndGet() < 3) error("no XML content") else node("ok")
        }, settleMs = 5, idleMs = 1000, retryMs = 20).use { snapshots ->
            snapshots.start()
            assertEquals("ok", snapshots.awaitFresh(2000, 2000).root.attr("label"))
        }
        HierarchySnapshots({ error("device offline") }, settleMs = 5, retryMs = 20).use { snapshots ->
            snapshots.start()
            val e = runCatching { snapshots.awaitFresh(100, 150) }.exceptionOrNull()
            assertTrue(e is IllegalStateException && e.message == "device offline")
        }
    }

    @Test
    fun `closing wakes a waiting tap`() {
        val snapshots = HierarchySnapshots({ Thread.sleep(5000); node("late") }, settleMs = 5)
        snapshots.start()
        Thread { Thread.sleep(100); snapshots.close() }.start()
        val e = runCatching { snapshots.awaitFresh(5000, 5000) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertSame(IllegalStateException::class.java, e!!.javaClass)
    }

    @Test
    fun `a slow capture gives a stale snapshot instead of blocking the tap`() {
        val screen = AtomicInteger(1)
        val slow = java.util.concurrent.atomic.AtomicBoolean(false)
        HierarchySnapshots({
            val label = "screen${screen.get()}"
            if (slow.get()) Thread.sleep(1500)
            node(label)
        }, settleMs = 10, idleMs = 1000).use { snapshots ->
            snapshots.start()
            assertEquals("screen1", snapshots.awaitFresh(2000, 2000).root.attr("label"))
            slow.set(true)
            screen.set(2)
            snapshots.inputDone()
            val started = System.nanoTime()
            val captured = snapshots.awaitFresh(200, 2000)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 800)
            assertEquals("screen1", captured.root.attr("label"))
            assertTrue(!captured.fresh)
        }
    }

    @Test
    fun `with no snapshot at all the tap keeps waiting and then reports the capture error`() {
        HierarchySnapshots({ error("no XML content") }, settleMs = 5, retryMs = 20).use { snapshots ->
            snapshots.start()
            val e = runCatching { snapshots.awaitFresh(50, 200) }.exceptionOrNull()
            assertTrue(e is IllegalStateException && e.message == "no XML content")
        }
    }
}
