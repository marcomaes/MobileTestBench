package io.github.marcomaes.mobiletestbench.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The editor action, its registry and the text it picks, without a device. */
class ShowLocatorActionTest : BasePlatformTestCase() {

    private class Recorder : LocatorTarget {
        val texts = mutableListOf<String>()
        override fun showLocator(text: String) { texts += text }
    }

    fun testRegistryRegistersAndOnlyOwnerUnregisters() {
        val registry = project.getService(LocatorTargets::class.java)
        assertNotNull(registry)
        val a = Recorder()
        val b = Recorder()
        registry.register(a)
        assertSame(a, registry.current)
        registry.register(b)
        registry.unregister(a)
        assertSame(b, registry.current)
        registry.unregister(b)
        assertNull(registry.current)
    }

    fun testViewerRegistersItselfAndUnregistersOnDispose() {
        val registry = project.getService(LocatorTargets::class.java)
        val panel = DeviceViewerPanel(project)
        assertSame(panel, registry.current)
        Disposer.dispose(panel)
        assertNull(registry.current)
    }

    fun testTextIsSelectionOrCaretLine() {
        myFixture.configureByText("t.ts", "first()\nawait screen.getByTestId(\"a\").tap()<caret>\nlast()\n")
        assertEquals("await screen.getByTestId(\"a\").tap()", EditorLocatorText.of(myFixture.editor))
        myFixture.editor.selectionModel.setSelection(0, 5)
        assertEquals("first", EditorLocatorText.of(myFixture.editor))
    }

    fun testEmptyDocument() {
        myFixture.configureByText("e.txt", "")
        assertEquals("", EditorLocatorText.of(myFixture.editor))
    }

    fun testActionIsRegisteredAndEnabledOnlyInAnEditor() {
        val action = ActionManager.getInstance().getAction("MobileTestBench.ShowLocatorOnDevice")
        assertNotNull(action)
        myFixture.configureByText("t.ts", "x")
        val withEditor = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, myFixture.editor).build()
        val on = TestActionEvent.createTestEvent(action, withEditor)
        action.update(on)
        assertTrue(on.presentation.isEnabledAndVisible)
        val without = TestActionEvent.createTestEvent(action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
        action.update(without)
        assertFalse(without.presentation.isEnabledAndVisible)
    }

    fun testActionPassesTheLineToTheRegisteredTarget() {
        val registry = project.getService(LocatorTargets::class.java)
        val recorder = Recorder()
        registry.register(recorder)
        try {
            myFixture.configureByText("t.ts", "driver.findElement(By.id(\"x\")).click()<caret>")
            val action = ActionManager.getInstance().getAction("MobileTestBench.ShowLocatorOnDevice")
            val ctx = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, myFixture.editor).build()
            action.actionPerformed(TestActionEvent.createTestEvent(action, ctx))
            assertEquals(listOf("driver.findElement(By.id(\"x\")).click()"), recorder.texts)
        } finally {
            registry.unregister(recorder)
        }
    }
}
