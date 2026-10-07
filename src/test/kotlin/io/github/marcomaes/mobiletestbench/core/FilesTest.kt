package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.files.AndroidFiles
import io.github.marcomaes.mobiletestbench.core.files.EntryKind
import io.github.marcomaes.mobiletestbench.core.files.FileEntry
import io.github.marcomaes.mobiletestbench.core.files.FileErrors
import io.github.marcomaes.mobiletestbench.core.files.FileFormat
import io.github.marcomaes.mobiletestbench.core.files.FileParsers
import io.github.marcomaes.mobiletestbench.core.files.FilePaths
import io.github.marcomaes.mobiletestbench.core.files.FileSort
import io.github.marcomaes.mobiletestbench.core.files.FilesException
import io.github.marcomaes.mobiletestbench.core.files.IosFiles
import io.github.marcomaes.mobiletestbench.core.files.SortKey
import io.github.marcomaes.mobiletestbench.core.files.runStreaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.ZoneId

class FilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private class Fake(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            return answer(command)
        }
    }

    private fun ok(text: String = "") = CommandResult(0, text.toByteArray(), "")
    private fun err(stderr: String, stdout: String = "", code: Int = 1) = CommandResult(code, stdout.toByteArray(), stderr)

    // paths

    @Test fun joinParentNameNormalize() {
        assertEquals("/sdcard/Download", FilePaths.join("/sdcard", "Download"))
        assertEquals("/a", FilePaths.join("/", "a"))
        assertEquals("/a/b", FilePaths.join("/a/", "b"))
        assertEquals("/a", FilePaths.parent("/a/b"))
        assertEquals("/", FilePaths.parent("/a"))
        assertEquals("/", FilePaths.parent("/"))
        assertEquals("b c", FilePaths.name("/a/b c/"))
        assertEquals("/a/c", FilePaths.normalize("/a//b/../c/./"))
        assertEquals("/", FilePaths.normalize("/../.."))
    }

    @Test fun rootBounds() {
        assertTrue(FilePaths.isInside("/x/y", "/x/y/z"))
        assertTrue(FilePaths.isInside("/x/y", "/x/y"))
        assertFalse(FilePaths.isInside("/x/y", "/x/yy"))
        assertFalse(FilePaths.isInside("/x/y", "/x/y/../z"))
        assertTrue(FilePaths.isInside("/", "/anything"))
        assertEquals("/x/y", FilePaths.up("/x", "/x/y/z"))
        assertNull(FilePaths.up("/x", "/x"))
        assertEquals("/", FilePaths.up("/", "/sdcard"))
        assertNull(FilePaths.up("/", "/"))
    }

    @Test fun shellQuoting() {
        assertEquals("'a b'", FilePaths.shellQuote("a b"))
        assertEquals("'it'\\''s \$HOME;x'", FilePaths.shellQuote("it's \$HOME;x"))
    }

    @Test fun nameProblems() {
        assertNull(FilePaths.nameProblem("jbv scout"))
        assertEquals("Enter a name", FilePaths.nameProblem("  "))
        assertTrue(FilePaths.nameProblem("a/b")!!.contains("/"))
        assertTrue(FilePaths.nameProblem("..")!!.contains(".."))
        assertTrue(FilePaths.nameProblem("a\nb")!!.contains("line"))
    }

    @Test fun sizesAndDates() {
        assertEquals("12 B", FileFormat.size(12))
        assertEquals("1.5 KB", FileFormat.size(1536))
        assertEquals("6.0 MB", FileFormat.size(6L * 1024 * 1024))
        assertEquals("2026-10-04 15:27", FileFormat.modified(1791120432000L, ZoneId.of("Europe/Amsterdam")))
        assertEquals("", FileFormat.modified(null))
    }

    @Test fun sortFoldersFirst() {
        val e = listOf(
            FileEntry("b.txt", "/b.txt", false, 5, 10),
            FileEntry("Zeta", "/Zeta", true, 0, 5),
            FileEntry("a.txt", "/a.txt", false, 50, 1),
            FileEntry("alpha", "/alpha", true, 0, 9),
        )
        assertEquals(listOf("alpha", "Zeta", "a.txt", "b.txt"), FileSort.sort(e).map { it.name })
        assertEquals(listOf("Zeta", "alpha", "b.txt", "a.txt"), FileSort.sort(e, SortKey.NAME, false).map { it.name })
        assertEquals(listOf("b.txt", "a.txt"), FileSort.sort(e, SortKey.SIZE).filter { !it.isDir }.map { it.name })
        assertEquals(listOf("b.txt", "a.txt"), FileSort.sort(e, SortKey.MODIFIED, false).filter { !it.isDir }.map { it.name })
    }

    // parsers

    @Test fun mobilecliLsAndroid() {
        val entries = FileParsers.parseMobilecliLs(fixture("mobilecli-fs-ls-android-root-symlinks.json"))
        val sdcard = entries.first { it.name == "sdcard" }
        assertEquals("/sdcard", sdcard.path)
        assertFalse(sdcard.isDir)
        assertTrue(entries.first { it.name == "data" }.isDir)
    }

    @Test fun mobilecliLsIosAppContainer() {
        val entries = FileParsers.parseMobilecliLs(fixture("mobilecli-fs-ls-ios-app-container.json"))
        assertEquals(listOf("Library", "tmp"), entries.map { it.name })
        assertTrue(entries.all { it.isDir && it.modifiedMillis != null })
        assertTrue(FileParsers.parentOfEntries(entries)!!.endsWith("/Containers/Data/Application/50A509E4-7327-4B18-A41C-ADA8C338B9B6"))
    }

    @Test fun mobilecliLsEmptyAndError() {
        assertTrue(FileParsers.parseMobilecliLs(fixture("mobilecli-fs-ls-empty.json")).isEmpty())
        assertNull(FileParsers.parentOfEntries(emptyList()))
        try {
            FileParsers.parseMobilecliLs("""{"status":"error","error":"failed to list files: no such file or directory"}""")
            fail()
        } catch (e: MobilecliException) {
            assertTrue(e.message!!.contains("no such file"))
        }
    }

    @Test fun statLines() {
        val out = """
            directory|4096|1791120446|/sdcard/Download/jbv scout/a
            regular file|12|1791120432|/sdcard/Download/jbv scout/my file.txt
            directory|4096|1791120484|/sdcard/Download/jbv scout/it's ${'$'}HOME;x
            symbolic link|7|1791120506|./lnk
            find: /data/data: Permission denied
            regular file|3|1|./a|b.txt
        """.trimIndent()
        val e = FileParsers.parseStatLines(out, "/sdcard/Download/jbv scout")
        assertEquals(listOf("a", "my file.txt", "it's \$HOME;x", "lnk", "a|b.txt"), e.map { it.name })
        assertTrue(e[0].isDir)
        assertEquals(0L, e[0].size)
        assertEquals(12L, e[1].size)
        assertEquals(1791120432000L, e[1].modifiedMillis)
        assertEquals("/sdcard/Download/jbv scout/my file.txt", e[1].path)
        assertTrue(e[3].isLink)
        assertFalse(e[3].isDir)
    }

    @Test fun statFixture() {
        val e = FileParsers.parseStatLines(fixture("android-run-as-find-stat.txt"), "/x")
        assertTrue(e.any { it.name == "allb.bin" && it.size == 10240L })
    }

    @Test fun errorMessages() {
        assertEquals("run-as: package not debuggable: com.x", FileErrors.message(err("", "run-as: package not debuggable: com.x"), "f"))
        assertEquals("boom", FileErrors.message(err("boom", "ignored"), "f"))
        assertEquals("fallback", FileErrors.message(err("", ""), "fallback"))
        assertTrue(FileErrors.appDataHint("run-as: package not debuggable: com.x", "com.x").contains("/sdcard/Android/data/com.x"))
        assertEquals("other", FileErrors.appDataHint("other", "com.x"))
        assertEquals("run-as: package not debuggable", FileErrors.appDataHint("run-as: package not debuggable", null))
    }

    // Android backend

    @Test fun androidDeviceStorageList() {
        val r = Fake { ok("directory|4096|1791120446|/sdcard/Download/a\n") }
        val files = AndroidFiles("adb", "emulator-5554", r)
        assertEquals("/sdcard", files.home)
        assertEquals("/", files.root)
        val list = files.list("/sdcard/Download")
        assertEquals("a", list.single().name)
        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "find '/sdcard/Download/' -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%n' {} +"),
            r.commands.single(),
        )
    }

    @Test fun androidEmptyDirAndPartialFailure() {
        assertTrue(AndroidFiles("adb", "s", Fake { ok() }).list("/sdcard").isEmpty())
        val partial = AndroidFiles("adb", "s", Fake { err("find: /x: Permission denied", "regular file|1|1|/a/b\n") })
        assertEquals(1, partial.list("/a").size)
        try {
            AndroidFiles("adb", "s", Fake { err("find: /data/data: Permission denied") }).list("/data/data")
            fail()
        } catch (e: FilesException) {
            assertTrue(e.message!!.contains("Permission denied"))
        }
    }

    @Test fun androidAppDataUsesRunAsWithRelativePaths() {
        val r = Fake { ok("regular file|440|1791120078|./AwOriginVisitLoggerPrefs.xml\n") }
        val files = AndroidFiles("adb", "s", r, "com.mobilenext.playground")
        assertEquals("/data/user/0/com.mobilenext.playground", files.root)
        assertEquals(files.root, files.home)
        assertFalse(files.canDownloadFolder)
        val list = files.list("/data/user/0/com.mobilenext.playground/shared_prefs")
        assertEquals("/data/user/0/com.mobilenext.playground/shared_prefs/AwOriginVisitLoggerPrefs.xml", list.single().path)
        val cmd = r.commands.single()
        assertEquals(listOf("adb", "-s", "s", "shell"), cmd.take(4))
        assertEquals(
            "run-as com.mobilenext.playground sh -c 'find '\\''shared_prefs/'\\'' -mindepth 1 -maxdepth 1 -exec stat -c '\\''%F|%s|%Y|%n'\\'' {} +'",
            cmd[4],
        )
        files.list(files.root)
        assertTrue(r.commands.last()[4].contains("find '\\''./'\\''"))
    }

    @Test fun androidAppDataRefusesOutsidePaths() {
        val files = AndroidFiles("adb", "s", Fake { ok() }, "com.x")
        try { files.list("/data/user/0/other"); fail() } catch (e: FilesException) { assertTrue(e.message!!.contains("outside")) }
        try { files.mkdir("/data/user/0/com.x/../other/z"); fail() } catch (e: FilesException) { assertTrue(e.message!!.contains("outside")) }
    }

    @Test fun androidNotDebuggableGivesHint() {
        val files = AndroidFiles("adb", "s", Fake { err("", "run-as: package not debuggable: com.android.chrome") }, "com.android.chrome")
        try { files.list(files.root); fail() } catch (e: FilesException) {
            assertTrue(e.message!!.contains("not debuggable"))
            assertTrue(e.message!!.contains("/sdcard/Android/data/com.android.chrome"))
        }
    }

    @Test fun androidKindReadMkdirDelete() {
        val r = Fake { c ->
            val s = c.last()
            when {
                s.startsWith("stat -L") -> ok("directory\n")
                s.startsWith("head -c") -> ok("hello")
                else -> ok()
            }
        }
        val files = AndroidFiles("adb", "s", r)
        assertEquals(EntryKind.DIR, files.kindOf("/sdcard"))
        assertEquals("hello", String(files.read("/sdcard/a b.txt", 100)))
        assertEquals("head -c 100 '/sdcard/a b.txt'", r.commands.last().last())
        files.mkdir("/sdcard/Download/it's")
        assertEquals("mkdir '/sdcard/Download/it'\\''s'", r.commands.last().last())
        files.delete(FileEntry("d", "/sdcard/d", true, 0))
        assertEquals("rm -r '/sdcard/d'", r.commands.last().last())
        files.delete(FileEntry("f", "/sdcard/f", false, 3))
        assertEquals("rm '/sdcard/f'", r.commands.last().last())
        val file = AndroidFiles("adb", "s", Fake { ok("regular file\n") })
        assertEquals(EntryKind.FILE, file.kindOf("/sdcard/x"))
    }

    @Test fun androidDeviceStoragePullAndPush() {
        val r = Fake { ok("1 file pulled") }
        val files = AndroidFiles("adb", "s", r)
        val folder = tmp.newFolder()
        val out = files.pull(FileEntry("my file.txt", "/sdcard/my file.txt", false, 12), folder)
        assertEquals(File(folder, "my file.txt"), out)
        assertEquals(listOf("adb", "-s", "s", "pull", "/sdcard/my file.txt", out.path), r.commands.last())
        files.pull(FileEntry("dir", "/sdcard/dir", true, 0), folder)
        assertEquals(listOf("adb", "-s", "s", "pull", "/sdcard/dir", folder.path), r.commands.last())
        val local = tmp.newFile("up.txt")
        assertEquals("/sdcard/Download/up.txt", files.push(local, "/sdcard/Download"))
        assertEquals(listOf("adb", "-s", "s", "push", local.path, "/sdcard/Download/up.txt"), r.commands.last())
        val failing = AndroidFiles("adb", "s", Fake { err("adb: error: failed to stat remote object '/x': No such file or directory") })
        try { failing.pull(FileEntry("x", "/x", false, 1), folder); fail() } catch (e: FilesException) { assertTrue(e.message!!.contains("No such file")) }
        assertFalse(File(folder, "x").exists())
    }

    /** A runner whose `start` runs a local shell instead of adb, to test the streaming of run-as pulls and pushes. */
    private class ShellRunner(val script: (List<String>) -> String) : CommandRunner(emptyMap()) {
        override fun start(command: List<String>): Process = ProcessBuilder("sh", "-c", script(command)).start()
    }

    @Test fun androidAppDataPullStreamsBinary() {
        val bytes = ByteArray(10240) { it.toByte() }
        val src = tmp.newFile("src.bin").also { it.writeBytes(bytes) }
        val files = AndroidFiles("adb", "s", ShellRunner { "cat '${src.path}'" }, "com.x")
        val folder = tmp.newFolder()
        val out = files.pull(FileEntry("src.bin", "/data/user/0/com.x/files/src.bin", false, 10240), folder)
        assertTrue(bytes.contentEquals(out.readBytes()))
    }

    @Test fun androidAppDataPullFailureDropsPartialFile() {
        val files = AndroidFiles("adb", "s", ShellRunner { "echo 'run-as: package not debuggable: com.x'; exit 1" }, "com.x")
        val folder = tmp.newFolder()
        try { files.pull(FileEntry("a", "/data/user/0/com.x/a", false, 1), folder); fail() } catch (e: FilesException) {
            assertTrue(e.message!!.contains("not debuggable"))
        }
        assertFalse(File(folder, "a").exists())
        try { files.pull(FileEntry("d", "/data/user/0/com.x/d", true, 0), folder); fail() } catch (e: FilesException) { assertTrue(e.message!!.contains("folder")) }
    }

    @Test fun androidAppDataPullKeepsExistingFileOnFailure() {
        val files = AndroidFiles("adb", "s", ShellRunner { "echo 'run-as: package not debuggable: com.x'; exit 1" }, "com.x")
        val folder = tmp.newFolder()
        File(folder, "a").writeText("old")
        try { files.pull(FileEntry("a", "/data/user/0/com.x/a", false, 1), folder); fail() } catch (_: FilesException) {}
        assertEquals("old", File(folder, "a").readText())
        assertEquals(listOf("a"), folder.list()!!.toList())
    }

    @Test fun runStreamingTimeoutThrows() {
        val out = File(tmp.newFolder(), "big")
        try { runStreaming(ShellRunner { "echo partial; sleep 30" }, listOf("x"), null, out, java.time.Duration.ofMillis(500)); fail() } catch (e: FilesException) {
            assertTrue(e.message!!.contains("Timed out"))
        }
    }

    @Test fun runStreamingKeepsStderrAndStdoutText() {
        val (code, text) = runStreaming(ShellRunner { "echo out-text; echo err-text >&2; exit 2" }, listOf("x"), null, null, java.time.Duration.ofSeconds(10))
        assertEquals(2, code)
        assertTrue(text.contains("out-text") && text.contains("err-text"))
    }

    @Test fun androidPackageNameIsValidated() {
        for (bad in listOf("com.x;reboot", "a b", "x\$(id)", "a|b", "`id`", "")) {
            try { AndroidFiles("adb", "s", Fake { ok("") }, bad); fail(bad) } catch (_: FilesException) {}
        }
        AndroidFiles("adb", "s", Fake { ok("") }, "com.mobilenext.playground")
    }

    @Test fun androidAppDataPushStreamsStdin() {
        val dest = File(tmp.newFolder(), "dest.bin")
        val bytes = ByteArray(5000) { (it * 7).toByte() }
        val local = File(tmp.newFolder(), "dest.bin").also { it.writeBytes(bytes) }
        val runner = ShellRunner { cmd ->
            assertEquals("run-as com.x sh -c 'cat > '\\''files/dest.bin'\\'''", cmd.last())
            "cat > '${dest.path}'"
        }
        val files = AndroidFiles("adb", "s", runner, "com.x")
        assertEquals("/data/user/0/com.x/files/dest.bin", files.push(local, "/data/user/0/com.x/files"))
        assertTrue(bytes.contentEquals(dest.readBytes()))
    }

    // iOS backend

    private val sim = "/Users/m/Library/Developer/CoreSimulator/Devices/U/data"

    @Test fun iosRootFromListing() {
        val root = IosFiles.deviceRoot { fixtureCall(it) }
        assertTrue(root.endsWith("/Containers/Data/Application/50A509E4-7327-4B18-A41C-ADA8C338B9B6"))
    }

    private fun fixtureCall(args: List<String>): String {
        assertEquals(listOf("fs", "ls"), args)
        return fixture("mobilecli-fs-ls-ios-app-container.json")
    }

    @Test fun iosContainerParsing() {
        assertEquals("/a/b", IosFiles.parseContainer(ok("/a/b/\n")))
        assertNull(IosFiles.parseContainer(ok("(null)\n")))
        assertNull(IosFiles.parseContainer(ok("")))
        assertNull(IosFiles.parseContainer(err("No such file or directory", code = 2)))
        try {
            IosFiles.appContainer(Fake { ok("(null)") }, "xcrun", "U", "com.apple.Preferences"); fail()
        } catch (e: FilesException) { assertTrue(e.message!!.contains("no data container")) }
        val r = Fake { ok("$sim/Containers/Data/Application/X/\n") }
        assertEquals("$sim/Containers/Data/Application/X", IosFiles.appContainer(r, "xcrun", "U", "com.x"))
        assertEquals(listOf("xcrun", "simctl", "get_app_container", "U", "com.x", "data"), r.commands.single())
    }

    @Test fun iosCommandsUseAbsolutePathsInsideRoot() {
        val calls = mutableListOf<List<String>>()
        val files = IosFiles({ calls += it; "{\"status\":\"ok\",\"data\":[]}" }, sim, "com.x")
        assertTrue(files.isAppData)
        files.list("$sim/Library")
        files.mkdir("$sim/tmp/new dir")
        files.delete(FileEntry("d", "$sim/tmp/d", true, 0))
        files.delete(FileEntry("f", "$sim/tmp/f", false, 1))
        val local = tmp.newFile("up.txt")
        assertEquals("$sim/tmp/up.txt", files.push(local, "$sim/tmp"))
        assertEquals(
            listOf(
                listOf("fs", "ls", "$sim/Library"),
                listOf("fs", "mkdir", "$sim/tmp/new dir"),
                listOf("fs", "rm", "-r", "$sim/tmp/d"),
                listOf("fs", "rm", "$sim/tmp/f"),
                listOf("fs", "push", local.path, "$sim/tmp/up.txt"),
            ),
            calls,
        )
        for (bad in listOf("/", "relative", "$sim/../x", "-rf")) {
            try { files.list(bad); fail(bad) } catch (e: FilesException) { assertTrue(e.message!!.contains("outside")) }
        }
    }

    @Test fun iosKindProbe() {
        val self = """{"status":"ok","data":[{"name":"f","path":"$sim/f","size":3,"modTime":"2026-10-04T15:27:12.47366815+02:00","isDir":false}]}"""
        val dir = """{"status":"ok","data":[{"name":"a","path":"$sim/d/a","size":3,"isDir":false},{"name":"b","path":"$sim/d/b","size":3,"isDir":false}]}"""
        val files = IosFiles({ a -> if (a.last().endsWith("/f")) self else if (a.last().endsWith("/e")) fixture("mobilecli-fs-ls-empty.json") else dir }, sim)
        assertEquals(EntryKind.FILE, files.kindOf("$sim/f"))
        assertEquals(EntryKind.DIR, files.kindOf("$sim/d"))
        assertEquals(EntryKind.DIR, files.kindOf("$sim/e"))
    }

    @Test fun iosErrorTextIsShortened() {
        val files = IosFiles({ throw MobilecliException("failed to list files: ls failed: open $sim/Library/x: no such file or directory") }, sim)
        try { files.list("$sim/Library/x"); fail() } catch (e: FilesException) {
            assertEquals("failed to list files: ls failed: open <root>/Library/x: no such file or directory", e.message)
        }
    }

    @Test fun iosReadPullAndFolderCopy() {
        val root = tmp.newFolder("root")
        File(root, "d").mkdirs()
        File(root, "d/a.txt").writeText("hello world")
        val calls = mutableListOf<List<String>>()
        val files = IosFiles({ calls += it; "{}" }, root.path)
        assertEquals("hello", String(files.read("${root.path}/d/a.txt", 5)))
        assertEquals("hello world", String(files.read("${root.path}/d/a.txt", 500)))
        try { files.read("${root.path}/d", 5); fail() } catch (e: FilesException) { assertTrue(e.message!!.contains("folder")) }
        try { files.read("${root.path}/nope", 5); fail() } catch (e: FilesException) { assertTrue(e.message!!.contains("cannot be read")) }
        val dl = tmp.newFolder("dl")
        files.pull(FileEntry("a.txt", "${root.path}/d/a.txt", false, 11), dl)
        assertEquals(listOf("fs", "pull", "${root.path}/d/a.txt", File(dl, "a.txt").path), calls.last())
        files.pull(FileEntry("d", "${root.path}/d", true, 0), dl)
        assertEquals("hello world", File(dl, "d/a.txt").readText())
    }
}
