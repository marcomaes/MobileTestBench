package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.files.FilePreview
import io.github.marcomaes.mobiletestbench.core.files.PlistException
import io.github.marcomaes.mobiletestbench.core.files.PlistFlatten
import io.github.marcomaes.mobiletestbench.core.files.PlistParser
import io.github.marcomaes.mobiletestbench.core.files.PlistValue
import io.github.marcomaes.mobiletestbench.core.files.Preview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FilePreviewTest {
    private fun text(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun bytes(name: String) = javaClass.getResource("/fixtures/$name")!!.readBytes()
    private fun preview(name: String, fixture: String) = FilePreview.build(name, bytes(fixture), bytes(fixture).size.toLong())
    private fun rows(p: Preview) = (p as Preview.Table).rows

    @Test fun sharedPreferencesAllTypes() {
        val t = preview("a.xml", "android-shared-prefs-all-types.xml") as Preview.Table
        assertEquals(listOf("Key", "Type", "Value"), t.columns)
        val byKey = t.rows.associateBy { it[0] }
        assertEquals(listOf("username", "string", "scout & co"), byKey.getValue("username"))
        assertEquals(listOf("onboarded", "boolean", "true"), byKey.getValue("onboarded"))
        assertEquals(listOf("launch_count", "int", "3"), byKey.getValue("launch_count"))
        assertEquals(listOf("last_seen", "long", "1791119957648"), byKey.getValue("last_seen"))
        assertEquals(listOf("ratio", "float", "0.5"), byKey.getValue("ratio"))
        assertEquals("line one\nline two", byKey.getValue("multi")[2])
        assertEquals("[a, b]", byKey.getValue("tags")[2])
        assertEquals("[]", byKey.getValue("empty_set")[2])
        assertEquals(8, t.rows.size)
    }

    @Test fun sharedPreferencesPlaygroundAndEncrypted() {
        assertTrue(rows(preview("playground_prefs.xml", "android-shared-prefs-playground.xml")).isNotEmpty())
        val enc = rows(preview("playground_secure_prefs.xml", "android-shared-prefs-encrypted-playground.xml"))
        assertTrue(enc.any { it[0].startsWith("__androidx_security_crypto") })
    }

    @Test fun xmlPlistWithDoctypeIsParsedWithoutFetchingTheDtd() {
        val r = rows(preview("x.plist", "ios-plist-all-types-xml.plist")).associateBy { it[0] }
        assertEquals(listOf("username", "string", "scoutuser"), r.getValue("username"))
        assertEquals(listOf("launchCount", "integer", "3"), r.getValue("launchCount"))
        assertEquals(listOf("ratio", "real", "0.5"), r.getValue("ratio"))
        assertEquals(listOf("onboarded", "bool", "true"), r.getValue("onboarded"))
        assertEquals(listOf("seen", "date", "2026-10-04T13:30:00Z"), r.getValue("seen"))
        assertEquals(listOf("token", "data", "5 bytes: 48656c6c6f"), r.getValue("token"))
        assertEquals(listOf("tags", "array", "2 items"), r.getValue("tags"))
        assertEquals("a", r.getValue("tags[0]")[2])
        assertEquals("v", r.getValue("nested.k")[2])
    }

    @Test fun binaryPlistMatchesTheXmlOne() {
        val xml = rows(preview("x.plist", "ios-plist-all-types-xml.plist")).sortedBy { it[0] }
        val bin = rows(preview("x.plist", "ios-plist-all-types-binary.plist")).sortedBy { it[0] }
        assertEquals(xml, bin)
    }

    @Test fun binaryPlistRealWorldHsts() {
        val v = PlistParser.parseBinary(bytes("ios-plist-binary-hsts.plist"))
        val rows = PlistFlatten.rows(v).associateBy { it[0] }
        assertEquals("dict", rows.getValue("com.apple.CFNetwork.defaultStorageSession")[1])
        assertEquals("true", rows.getValue("com.apple.CFNetwork.defaultStorageSession.en.wikipedia.org.HSTS Host")[2])
        assertEquals("812812958.055501", rows.getValue("com.apple.CFNetwork.defaultStorageSession.en.wikipedia.org.Create Time")[2])
    }

    @Test fun binaryPlistRejectsGarbage() {
        val good = bytes("ios-plist-all-types-binary.plist")
        for (bad in listOf(good.copyOf(20), good.copyOf(good.size - 5), "bplist00".toByteArray() + ByteArray(80) { 0x7f })) {
            try { PlistParser.parseBinary(bad); fail() } catch (_: PlistException) {}
        }
        val p = FilePreview.build("x.plist", "bplist00".toByteArray() + ByteArray(80) { 0x7f }, 88)
        assertTrue((p as Preview.Message).text.contains("Unreadable"))
    }

    @Test fun binaryPlistSelfReferenceIsRejected() {
        // object 0 is an array of two references to itself: depth alone would allow 2^64 visits.
        val out = java.io.ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        out.write(0xA2); out.write(0); out.write(0)
        out.write(8)  // offset table: object 0 at 8
        out.write(ByteArray(6)); out.write(1); out.write(1)
        out.write(java.nio.ByteBuffer.allocate(8).putLong(1).array())
        out.write(java.nio.ByteBuffer.allocate(8).putLong(0).array())
        out.write(java.nio.ByteBuffer.allocate(8).putLong(11).array())
        try { PlistParser.parseBinary(out.toByteArray()); fail() } catch (_: PlistException) {}
    }

    @Test fun binaryPlistValues() {
        // dict {"i": 300, "n": -2, "s": "é"(UTF-16), "u": uid 5, "r": 1.5 (float)}
        val v = PlistParser.parseBinary(handBuiltPlist())
        val d = (v as PlistValue.Dict).entries.toMap()
        assertEquals(java.math.BigInteger.valueOf(300), (d.getValue("i") as PlistValue.Int).value)
        assertEquals(java.math.BigInteger.valueOf(-2), (d.getValue("n") as PlistValue.Int).value)
        assertEquals("\u00e9", (d.getValue("s") as PlistValue.Str).value)
        assertEquals(java.math.BigInteger.valueOf(5), (d.getValue("u") as PlistValue.Uid).value)
        assertEquals(1.5, (d.getValue("r") as PlistValue.Real).value, 0.0)
    }

    private fun handBuiltPlist(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val offs = mutableListOf<Int>()
        fun obj(vararg b: Int) { offs += out.size() + 0; b.forEach { out.write(it) } }
        out.write("bplist00".toByteArray())
        // object 0: dict of 5 pairs, keys refs 1..5, values refs 6..10 (refSize 1)
        offs += out.size(); out.write(0xD5); (1..5).forEach { out.write(it) }; (6..10).forEach { out.write(it) }
        for (k in listOf("i", "n", "s", "u", "r")) { offs += out.size(); out.write(0x51); out.write(k.toByteArray()) }
        offs += out.size(); out.write(0x11); out.write(0x01); out.write(0x2c)       // 300 (2 bytes)
        offs += out.size(); out.write(0x13); out.write(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -2)) // -2 (8 bytes)
        offs += out.size(); out.write(0x61); out.write(0x00); out.write(0xe9)        // "é"
        offs += out.size(); out.write(0x80); out.write(5)                            // uid 5
        offs += out.size(); out.write(0x22); out.write(java.nio.ByteBuffer.allocate(4).putFloat(1.5f).array())
        val tableAt = out.size()
        offs.forEach { out.write(it) }
        out.write(ByteArray(6)); out.write(1); out.write(1)
        out.write(java.nio.ByteBuffer.allocate(8).putLong(offs.size.toLong()).array())
        out.write(java.nio.ByteBuffer.allocate(8).putLong(0).array())
        out.write(java.nio.ByteBuffer.allocate(8).putLong(tableAt.toLong()).array())
        return out.toByteArray()
    }

    @Test fun jsonIsPrettyPrinted() {
        val p = FilePreview.build("a.json", """{"a":1,"b":[1,2],"c":{"d":"x<y"}}""".toByteArray(), 30) as Preview.Text
        assertEquals("json", p.format)
        assertTrue(p.text.contains("\n  \"a\": 1"))
        assertTrue(p.text.contains("x<y"))
    }

    @Test fun invalidJsonFallsBackToText() {
        val p = FilePreview.build("a.json", "{not json".toByteArray(), 9) as Preview.Text
        assertEquals("text", p.format)
    }

    @Test fun otherXmlIsPrettyPrinted() {
        val p = FilePreview.build("a.xml", "<?xml version=\"1.0\"?><root><a>1</a><b><c/></b></root>".toByteArray(), 50) as Preview.Text
        assertEquals("xml", p.format)
        assertEquals("<root>\n  <a>1</a>\n  <b>\n    <c/>\n  </b>\n</root>", p.text)
    }

    @Test fun mapWithUntypedChildrenIsNotPrefs() {
        val p = FilePreview.build("a.xml", "<map><thing a=\"1\"/></map>".toByteArray(), 30)
        assertTrue(p is Preview.Text)
    }

    @Test fun xmlWithExternalEntityIsNotFetched() {
        val xml = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>"
        val p = FilePreview.build("a.xml", xml.toByteArray(), xml.length.toLong())
        assertTrue(!(p as? Preview.Text)?.text.orEmpty().contains("root:"))
    }

    @Test fun plainTextAndBinaryAndLimits() {
        assertEquals("hello\nworld", (FilePreview.build("a.txt", "hello\nworld".toByteArray(), 11) as Preview.Text).text)
        assertEquals("Empty file", (FilePreview.build("a", ByteArray(0), 0) as Preview.Message).text)
        val bin = FilePreview.build("a.bin", byteArrayOf(1, 2, 0, 3), 4) as Preview.Message
        assertTrue(bin.text.contains("Binary") && bin.text.contains("Download"))
        val badUtf = FilePreview.build("a.bin", byteArrayOf(0xC3.toByte(), 0x28), 2)
        assertTrue(badUtf is Preview.Message)
        val big = FilePreview.build("a.txt", "x".toByteArray(), 6L * 1024 * 1024) as Preview.Message
        assertTrue(big.text.contains("6.0 MB") && big.text.contains("Download"))
        val over = FilePreview.build("a.txt", ByteArray(FilePreview.MAX_BYTES + 1) { 'a'.code.toByte() }, null)
        assertTrue(over is Preview.Message)
    }

    @Test fun sqliteIsRecognisedByMagic() {
        val head = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(100)
        assertTrue(FilePreview.build("httpstorages.sqlite", head, 4096) is Preview.Sqlite)
        // detected before the size cap: a big database still offers the pull
        assertTrue(FilePreview.build("big.db", head, 50L * 1024 * 1024) is Preview.Sqlite)
    }
}
