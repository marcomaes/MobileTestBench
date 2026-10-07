package io.github.marcomaes.mobiletestbench.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.marcomaes.mobiletestbench.core.logs.StackFrame

/** The Log tab constructs without a device, and stack frames resolve to project files by name and package. */
class LogPanelTest : BasePlatformTestCase() {

    fun testConstructsWithoutADevice() {
        val panel = LogPanel(project)
        panel.attach(null)
        panel.dispose()
    }

    fun testResolvesByFileName() {
        val foo = myFixture.addFileToProject("src/com/example/ui/Foo.java", "package com.example.ui; class Foo {}")
        myFixture.addFileToProject("src/org/other/Foo.java", "package org.other; class Foo {}")
        val found = StackFrameOpener.find(project, StackFrame("com.example.ui.Foo", "bar", "Foo.java", 1))
        assertEquals(foo.virtualFile, found)
    }

    fun testUnknownFileIsNull() {
        assertNull(StackFrameOpener.find(project, StackFrame("android.app.Foo", "bar", "NoSuchFile.java", 1)))
    }
}
