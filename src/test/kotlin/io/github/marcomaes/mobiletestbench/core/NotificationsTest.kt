package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.notifications.AndroidNotifications
import io.github.marcomaes.mobiletestbench.core.notifications.AndroidPost
import io.github.marcomaes.mobiletestbench.core.notifications.ApnsForm
import io.github.marcomaes.mobiletestbench.core.notifications.ApnsPayloads
import io.github.marcomaes.mobiletestbench.core.notifications.HistoryEntry
import io.github.marcomaes.mobiletestbench.core.notifications.IntentSpec
import io.github.marcomaes.mobiletestbench.core.notifications.IosNotifications
import io.github.marcomaes.mobiletestbench.core.notifications.NotificationCommands
import io.github.marcomaes.mobiletestbench.core.notifications.PayloadHistory
import io.github.marcomaes.mobiletestbench.core.notifications.PostStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration

class NotificationsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private class FakeRunner(val answer: (List<String>) -> CommandResult) : CommandRunner(emptyMap()) {
        val commands = mutableListOf<List<String>>()
        var fileContent: String? = null
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            commands += command
            command.lastOrNull()?.takeIf { it.endsWith(".json") }?.let { fileContent = File(it).readText() }
            return answer(command)
        }
    }

    private fun res(out: String, exit: Int = 0, err: String = "") = CommandResult(exit, out.toByteArray(), err)

    @Test fun buildsPayloadFromForm() {
        val p = ApnsPayloads.build(ApnsForm(title = "Hi", subtitle = "Sub", body = "Body", badge = "3", sound = "default", category = "MSG", customJson = """{"id":42,"deep":{"a":[1,2]}}"""))
        assertNull(p.problem)
        assertEquals("""{"aps":{"alert":{"title":"Hi","subtitle":"Sub","body":"Body"},"badge":3,"sound":"default","category":"MSG"},"id":42,"deep":{"a":[1,2]}}""", p.compact)
        assertTrue(p.pretty.contains("\n"))
        assertEquals(p.compact.length, p.size)
    }

    @Test fun omitsBlankFieldsAndEscapes() {
        val p = ApnsPayloads.build(ApnsForm(body = "say \"hi\" <b> \\ \n tab\t ünï ✓"))
        assertNull(p.problem)
        assertEquals("""{"aps":{"alert":{"body":"say \"hi\" <b> \\ \n tab\t ünï ✓"}}}""", p.compact)
        assertTrue(p.size > p.compact.length)
    }

    @Test fun formProblems() {
        assertNotNull(ApnsPayloads.build(ApnsForm()).problem)
        assertNotNull(ApnsPayloads.build(ApnsForm(body = "x", badge = "x")).problem)
        assertNotNull(ApnsPayloads.build(ApnsForm(body = "x", badge = "-1")).problem)
        assertNotNull(ApnsPayloads.build(ApnsForm(body = "x", customJson = "[1]")).problem)
        assertNotNull(ApnsPayloads.build(ApnsForm(body = "x", customJson = "{bad")).problem)
        assertNotNull(ApnsPayloads.build(ApnsForm(body = "x", customJson = """{"aps":{}}""")).problem)
        assertNull(ApnsPayloads.build(ApnsForm(badge = "0")).problem)
    }

    @Test fun inspectsRawPayloads() {
        assertNull(ApnsPayloads.inspect("""{"aps":{"alert":"x"}}""").problem)
        assertTrue(ApnsPayloads.inspect("{nope").problem!!.startsWith("Not valid JSON"))
        assertNotNull(ApnsPayloads.inspect("[1]").problem)
        assertNotNull(ApnsPayloads.inspect("").problem)
        assertNotNull(ApnsPayloads.inspect("""{"x":1}""").problem)
        assertNotNull(ApnsPayloads.inspect("""{"aps":"x"}""").problem)
        assertNotNull(ApnsPayloads.inspect("""{"aps":{}}""").problem)
        assertNotNull(ApnsPayloads.inspect("""{"aps":{"badge":"x"}}""").problem)
        assertNull(ApnsPayloads.inspect("""{"aps":{"badge":2}}""").problem)
        assertEquals("""{"aps":{"alert":"x"}}""", ApnsPayloads.inspect("{ \"aps\" : { \"alert\" : \"x\" } }").compact)
    }

    @Test fun sizeLimitIsInclusiveAndCountsBytes() {
        fun payloadOfSize(n: Int): String {
            val base = """{"aps":{"alert":""}}"""
            return """{"aps":{"alert":"${"a".repeat(n - base.length)}"}}"""
        }
        assertEquals(4096, ApnsPayloads.inspect(payloadOfSize(4096)).size)
        assertFalse(ApnsPayloads.inspect(payloadOfSize(4096)).oversize)
        assertTrue(ApnsPayloads.inspect(payloadOfSize(4097)).oversize)
        // a 3 byte character counts three times
        val multi = ApnsPayloads.build(ApnsForm(body = "日".repeat(1400)))
        assertTrue(multi.oversize)
        assertEquals(3, ApnsPayloads.byteSize("日"))
    }

    @Test fun shellQuoteProtectsEverything() {
        assertEquals("'plain'", NotificationCommands.shellQuote("plain"))
        assertEquals("'it'\\''s'", NotificationCommands.shellQuote("it's"))
        assertEquals("'\$HOME; & | `x` \"q\" ünï ✓ 日本'", NotificationCommands.shellQuote("\$HOME; & | `x` \"q\" ünï ✓ 日本"))
        assertEquals("''", NotificationCommands.shellQuote(""))
    }

    @Test fun postCommandsPerStyle() {
        assertEquals("cmd notification post -t 'Hello title' 'tag' 'Body text'", NotificationCommands.androidPost(AndroidPost(title = "Hello title", text = "Body text", tag = "tag")))
        assertEquals("cmd notification post -S bigtext -t 'T' 'tag' 'text'", NotificationCommands.androidPost(AndroidPost("T", "text", PostStyle.BIGTEXT, "tag")))
        assertEquals("cmd notification post -S inbox --line 'line one' --line 'line two' -t 'T' 'tag' 'summary'",
            NotificationCommands.androidPost(AndroidPost("T", "summary", PostStyle.INBOX, "tag", lines = listOf("line one", "", "line two"))))
        assertEquals("cmd notification post -S messaging --conversation 'Chat' --message 'Bob:hi' --message 'Ann:yo' 'tag' 'x'",
            NotificationCommands.androidPost(AndroidPost("", "x", PostStyle.MESSAGING, "tag", conversation = "Chat", messages = listOf("Bob:hi", "Ann:yo"))))
        assertEquals("cmd notification post -t 'it'\\''s \"q\" \$HOME; & |' 't' 'ünï ✓ 日本'",
            NotificationCommands.androidPost(AndroidPost("it's \"q\" \$HOME; & |", "ünï ✓ 日本", tag = "t")))
    }

    @Test fun postValidation() {
        assertNull(NotificationCommands.postProblem(AndroidPost(text = "x")))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "")))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "x", tag = " ")))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "a\nb")))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(title = "a\nb", text = "x")))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "x", style = PostStyle.INBOX)))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "x", style = PostStyle.MESSAGING, conversation = "c")))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "x", style = PostStyle.MESSAGING, conversation = "c", messages = listOf("nocolon"))))
        assertNotNull(NotificationCommands.postProblem(AndroidPost(text = "x", style = PostStyle.MESSAGING, messages = listOf("a:b"))))
        assertNull(NotificationCommands.postProblem(AndroidPost(text = "x", style = PostStyle.MESSAGING, conversation = "c", messages = listOf("a:b"))))
    }

    @Test fun parsesExtras() {
        val (extras, err) = NotificationCommands.parseExtras("# comment\n\nname=a b=c\ni:n=5\nz:b=true\nl:big=123\nf:x=1.5\ns:k=v\n")
        assertNull(err)
        assertEquals(listOf("--es" to "name", "--ei" to "n", "--ez" to "b", "--el" to "big", "--ef" to "x", "--es" to "k"), extras.map { it.flag to it.key })
        assertEquals("a b=c", extras[0].value)
        assertNotNull(NotificationCommands.parseExtras("novalue").second)
        assertNotNull(NotificationCommands.parseExtras("=x").second)
        assertNotNull(NotificationCommands.parseExtras("i:n=abc").second)
        assertNotNull(NotificationCommands.parseExtras("z:b=yes").second)
        assertNotNull(NotificationCommands.parseExtras("a b=c").second)
    }

    @Test fun broadcastCommand() {
        val spec = IntentSpec("com.example.TEST", "com.pkg", "com.pkg/.Receiver", "key=v w\ni:n=5\nz:b=true")
        assertNull(NotificationCommands.broadcastProblem(spec))
        assertEquals("am broadcast -a 'com.example.TEST' -n 'com.pkg/.Receiver' --es 'key' 'v w' --ei 'n' '5' --ez 'b' 'true'", NotificationCommands.androidBroadcast(spec))
        assertEquals("am broadcast -a 'x' -p 'com.pkg'", NotificationCommands.androidBroadcast(IntentSpec("x", "com.pkg")))
        assertEquals("am broadcast -a 'it'\\''s'", NotificationCommands.androidBroadcast(IntentSpec("it's")))
        assertNotNull(NotificationCommands.broadcastProblem(IntentSpec()))
        assertNotNull(NotificationCommands.broadcastProblem(IntentSpec(component = "NoSlash")))
        assertNotNull(NotificationCommands.broadcastProblem(IntentSpec(action = "a b")))
        assertNotNull(NotificationCommands.broadcastProblem(IntentSpec(action = "a", extras = "bad")))
    }

    @Test fun commandIsOneArgumentForAdb() {
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "cmd notification post -t 'a b' 't' 'x'"), NotificationCommands.adbShell("adb", "emulator-5554", "cmd notification post -t 'a b' 't' 'x'"))
        assertEquals(listOf("xcrun", "simctl", "push", "U", "com.app", "/tmp/p.json"), NotificationCommands.iosPush("xcrun", "U", "com.app", "/tmp/p.json"))
    }

    @Test fun validatesBundleIds() {
        assertTrue(NotificationCommands.validBundleId("com.mobilenext.playground"))
        assertFalse(NotificationCommands.validBundleId(""))
        assertFalse(NotificationCommands.validBundleId("-x"))
        assertFalse(NotificationCommands.validBundleId("a b"))
    }

    @Test fun interpretsIosFixtures() {
        val text = fixture("ios-simctl-push-outputs.txt")
        assertTrue(text.contains("Notification sent to 'com.mobilenext.playground'"))
        val ok = NotificationCommands.interpretIosPush(0, "Notification sent to 'com.mobilenext.playground'\n", "")
        assertFalse(ok.error)
        assertEquals("Notification sent to 'com.mobilenext.playground'", ok.text)
        // invalid JSON exits 0 with empty stdout: still an error, stderr verbatim
        val bad = NotificationCommands.interpretIosPush(0, "", "An error was encountered processing the command (domain=NSCocoaErrorDomain, code=3840):\nThe data couldn’t be read")
        assertTrue(bad.error)
        assertTrue(bad.text.startsWith("An error was encountered"))
        val big = NotificationCommands.interpretIosPush(28, "", "Payload too large, must be 4096 bytes or less")
        assertTrue(big.error)
        assertTrue(NotificationCommands.interpretIosPush(1, "", "").error)
    }

    /** One captured command of a fixture file: its "$ ..." header, the output without the "[exit N]" line, and the exit code when recorded. */
    private class Sample(val header: String, val output: String, val exit: Int?)

    private fun samples(name: String): List<Sample> {
        val out = mutableListOf<Sample>()
        var header: String? = null
        val body = mutableListOf<String>()
        var exit: Int? = null
        fun flush() { header?.let { out += Sample(it, body.joinToString("\n"), exit ?: Regex("""\(exit (\d+)\)""").find(it)?.groupValues?.get(1)?.toInt()) } }
        for (line in fixture(name).lines()) {
            when {
                line.startsWith("$ ") -> { flush(); header = line; body.clear(); exit = null }
                line.startsWith("[exit") -> exit = line.removePrefix("[exit").removeSuffix("]").trim().toIntOrNull()
                else -> body += line
            }
        }
        flush()
        return out
    }

    private fun sample(name: String, headerPart: String) = samples(name).first { it.header.contains(headerPart) }

    @Test fun capturedSimctlOutputsGoThroughTheParser() {
        val ok = sample("ios-simctl-push-outputs.txt", "(ok)")
        val r = NotificationCommands.interpretIosPush(0, ok.output, "")
        assertFalse(r.error)
        assertEquals("Notification sent to 'com.mobilenext.playground'", r.text)
        // an unknown bundle id is still reported as sent (the simulator does not check)
        assertFalse(NotificationCommands.interpretIosPush(0, sample("ios-simctl-push-outputs.txt", "unknown bundle").output, "").error)
        // invalid JSON: exit 0, stdout empty, error on stderr
        val bad = sample("ios-simctl-push-outputs.txt", "invalid JSON")
        val badR = NotificationCommands.interpretIosPush(0, "", bad.output)
        assertTrue(badR.error)
        assertTrue(badR.text.startsWith("An error was encountered processing the command (domain=NSCocoaErrorDomain, code=3840)"))
        for (h in listOf("missing aps", "aps wrong value", "root not an object", "too large", "missing file", "invalid device")) {
            val s = sample("ios-simctl-push-outputs.txt", h)
            val res = NotificationCommands.interpretIosPush(s.exit ?: 1, "", s.output)
            assertTrue(h, res.error)
            assertEquals(h, s.output.trim(), res.text)
        }
    }

    @Test fun capturedBroadcastOutputsGoThroughTheParser() {
        val all = samples("android-am-broadcast-outputs.txt")
        assertEquals(4, all.size)
        for (s in all.take(2)) {
            val r = NotificationCommands.interpretBroadcast(0, s.output, "")
            assertFalse(s.header, r.error)
            assertTrue(r.text.endsWith("Broadcast completed: result=0"))
            assertTrue(r.text.startsWith("Broadcasting: Intent {"))
        }
        val noIntent = NotificationCommands.interpretBroadcast(255, all[2].output, "")
        assertTrue(noIntent.error)
        assertEquals("java.lang.IllegalArgumentException: No intent supplied", noIntent.text)
        val badNumber = NotificationCommands.interpretBroadcast(255, all[3].output, "")
        assertTrue(badNumber.error)
        assertEquals("java.lang.NumberFormatException: For input string: \"notanumber\"", badNumber.text)
    }

    @Test fun capturedAndroidPostOutputsGoThroughTheParser() {
        val f = "android-cmd-notification-post.txt"
        val ok = NotificationCommands.interpretAndroidPost(0, sample(f, "-t 'Hello title'").output, "")
        assertFalse(ok.error)
        assertTrue(ok.text.startsWith("posting for user 0:"))
        val bad = sample(f, "-S foo")
        val r = NotificationCommands.interpretAndroidPost(bad.exit ?: 0, bad.output, "")
        assertTrue(r.error)
        assertTrue(r.text.contains("unrecognized notification style: foo"))
    }

    @Test fun interpretsAndroidOutputs() {
        val post = NotificationCommands.interpretAndroidPost(0, "posting for user 0:\n  Notification(channel=shell_cmd ...)\n", "")
        assertFalse(post.error)
        assertEquals("posting for user 0:\n  Notification(channel=shell_cmd ...)", post.text)
        assertTrue(NotificationCommands.interpretAndroidPost(0, "Error occurred. Check logcat for details. unrecognized notification style: foo", "").error)
        assertTrue(NotificationCommands.interpretAndroidPost(0, "Unknown command: cancel", "").error)
        assertTrue(fixture("android-cmd-notification-post.txt").contains("-S|--style"))
    }

    @Test fun interpretsBroadcastFixtures() {
        val fx = fixture("android-am-broadcast-outputs.txt")
        assertTrue(fx.contains("Broadcast completed: result=0"))
        val ok = NotificationCommands.interpretBroadcast(0, "Broadcasting: Intent { act=a }\nBroadcast completed: result=0\n", "")
        assertFalse(ok.error)
        assertEquals("Broadcasting: Intent { act=a } | Broadcast completed: result=0", ok.text)
        val noIntent = NotificationCommands.interpretBroadcast(255, "\nException occurred while executing 'broadcast':\njava.lang.IllegalArgumentException: No intent supplied\n\tat android.content.Intent.parseCommandArgs(Intent.java:9181)\n", "")
        assertTrue(noIntent.error)
        assertEquals("java.lang.IllegalArgumentException: No intent supplied", noIntent.text)
        assertTrue(NotificationCommands.interpretBroadcast(0, "", "").error)
    }

    @Test fun countsShellNotifications() {
        assertEquals(1, NotificationCommands.shellNotificationCount("0|com.android.shell|2020|jbvm_t2|2000\n0|com.android.vending|5|x|10100\n"))
        assertEquals(0, NotificationCommands.shellNotificationCount("0|com.android.vending|5|x|10100\n"))
        assertEquals(0, NotificationCommands.shellNotificationCount(""))
    }

    @Test fun iosPushWritesCompactPayloadToTempFileAndDeletesIt() {
        val runner = FakeRunner { res("Notification sent to 'com.app'\n") }
        val r = IosNotifications("xcrun", "UDID", runner).push("com.app", """{"aps":{"alert":"é"}}""")
        assertFalse(r.error)
        assertEquals("""{"aps":{"alert":"é"}}""", runner.fileContent)
        val cmd = runner.commands.single()
        assertEquals(listOf("xcrun", "simctl", "push", "UDID", "com.app"), cmd.take(5))
        assertFalse(File(cmd.last()).exists())
    }

    @Test fun androidBackendSendsOneShellString() {
        val runner = FakeRunner { c ->
            val s = c.last()
            when {
                s.startsWith("cmd notification list") -> res("0|com.android.vending|5|x|1\n")
                s.startsWith("cmd notification post") -> res("posting for user 0:\n")
                else -> res("Broadcast completed: result=0\n")
            }
        }
        val b = AndroidNotifications("adb", "emulator-5554", runner)
        assertFalse(b.post(AndroidPost(title = "a b", text = "x", tag = "t")).error)
        assertEquals(listOf("adb", "-s", "emulator-5554", "shell", "cmd notification post -t 'a b' 't' 'x'"), runner.commands[0])
        assertFalse(b.broadcast(IntentSpec("act")).error)
        assertEquals("am broadcast -a 'act'", runner.commands[1].last())
        val cleared = b.clearShell()
        assertFalse(cleared.error)
        assertEquals(NotificationCommands.CLEAR_SHELL, runner.commands[2].last())
        assertEquals(NotificationCommands.LIST, runner.commands[3].last())
    }

    @Test fun clearReportsRemainingNotifications() {
        val runner = FakeRunner { c -> if (c.last() == NotificationCommands.LIST) res("0|com.android.shell|2020|t|2000\n") else res("Result: Parcel(...)") }
        val r = AndroidNotifications("adb", "s", runner).clearShell()
        assertTrue(r.error)
        assertTrue(r.text.startsWith("1 shell notification"))
    }

    @Test fun runnerFailureBecomesErrorResult() {
        val runner = object : CommandRunner(emptyMap()) {
            override fun run(command: List<String>, timeout: Duration): CommandResult = throw java.io.IOException("adb not found")
        }
        val r = AndroidNotifications("adb", "s", runner).post(AndroidPost(text = "x"))
        assertTrue(r.error)
        assertEquals("adb not found", r.text)
    }

    @Test fun historyKeepsNewestFirstDedupesAndCaps() {
        var list = emptyList<HistoryEntry>()
        for (i in 1..20) list = PayloadHistory.add(list, HistoryEntry("ios", mapOf("bundle" to "b", "payload" to """{"aps":{"badge":$i}}""")))
        assertEquals(15, list.size)
        assertTrue(list.first().fields["payload"]!!.contains("20"))
        assertTrue(list.last().fields["payload"]!!.contains("6}"))
        val again = HistoryEntry("ios", mapOf("bundle" to "b", "payload" to """{"aps":{"badge":10}}"""))
        list = PayloadHistory.add(list, again)
        assertEquals(15, list.size)
        assertEquals(again, list.first())
        assertEquals(1, list.count { it == again })
    }

    @Test fun historySerializesAndSkipsBadLines() {
        val e = HistoryEntry("post", mapOf("title" to "it's \"q\"\nline", "text" to "日本", "tag" to "t"))
        val line = PayloadHistory.serialize(e)
        assertFalse(line.contains("\n"))
        assertEquals(e, PayloadHistory.parse(line))
        assertNull(PayloadHistory.parse("garbage"))
        assertNull(PayloadHistory.parse("""{"kind":"x"}"""))
        assertEquals(listOf(e), PayloadHistory.load(listOf("garbage", line)))
        assertEquals(15, PayloadHistory.load(List(30) { PayloadHistory.serialize(HistoryEntry("intent", mapOf("action" to "a$it"))) }).size)
        assertTrue(e.label.startsWith("Post t:"))
        assertTrue(HistoryEntry("ios", mapOf("bundle" to "com.a", "payload" to """{ "aps" : { "alert": "x" } }""")).label.contains("""{"aps":{"alert":"x"}}"""))
    }
}
