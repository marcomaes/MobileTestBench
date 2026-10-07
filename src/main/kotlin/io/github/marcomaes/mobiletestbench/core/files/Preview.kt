package io.github.marcomaes.mobiletestbench.core.files

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** What the preview pane shows for a file. */
sealed class Preview {
    /** Readable text; [format] is "text", "json" or "xml" (pretty printed). */
    data class Text(val text: String, val format: String) : Preview()

    /** Key/value style data (SharedPreferences, property lists). */
    data class Table(val title: String, val columns: List<String>, val rows: List<List<String>>) : Preview()

    /** Nothing to show, with the reason. */
    data class Message(val text: String) : Preview()

    /** A SQLite database: no viewer, the UI offers pulling it. */
    data class Sqlite(val text: String) : Preview()
}

/** Pure preview building: classifies a file by its bytes and name and formats it. */
object FilePreview {
    /** Previews read at most this much; larger files say "use Download". */
    const val MAX_BYTES = 256 * 1024

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /** [bytes] is the start of the file (at most [MAX_BYTES] + 1 of it), [totalSize] the size from the listing (null: unknown). */
    fun build(name: String, bytes: ByteArray, totalSize: Long?): Preview {
        if (bytes.startsWith(SQLITE_MAGIC)) return Preview.Sqlite("SQLite database (${FileFormat.size(totalSize ?: bytes.size.toLong())}). There is no viewer here.")
        if ((totalSize ?: bytes.size.toLong()) > MAX_BYTES || bytes.size > MAX_BYTES)
            return Preview.Message("Too large to preview (${FileFormat.size(totalSize ?: bytes.size.toLong())}, the limit is ${FileFormat.size(MAX_BYTES.toLong())}). Use Download.")
        if (bytes.isEmpty()) return Preview.Message("Empty file")
        if (bytes.startsWith("bplist00".toByteArray())) return runCatching { plistTable(PlistParser.parseBinary(bytes), name) }
            .getOrElse { Preview.Message("Unreadable binary property list (${it.message}). Use Download.") }
        val text = decodeUtf8(bytes) ?: return Preview.Message("Binary file (${FileFormat.size(bytes.size.toLong())}). Use Download.")
        val trimmed = text.trimStart('﻿').trim()
        if (trimmed.startsWith("<")) xml(name, trimmed)?.let { return it }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) json(trimmed)?.let { return it }
        return Preview.Text(text, "text")
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** The text when [bytes] are valid UTF-8 without NUL bytes in the first 8 KB, else null. */
    fun decodeUtf8(bytes: ByteArray): String? {
        if ((0 until minOf(bytes.size, 8192)).any { bytes[it].toInt() == 0 }) return null
        return runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    private fun json(text: String): Preview? = runCatching {
        Preview.Text(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(text)), "json")
    }.getOrNull()

    private fun xml(name: String, text: String): Preview? {
        val doc = runCatching { secureBuilder().parse(InputSource(StringReader(text))) }.getOrNull() ?: return null
        val root = doc.documentElement
        if (root.tagName == "plist") return runCatching { plistTable(PlistParser.parseXml(root), name) }.getOrElse { Preview.Message("Unreadable property list (${it.message})") }
        if (root.tagName == "map") SharedPrefs.table(root)?.let { return it }
        return runCatching { Preview.Text(prettyXml(doc), "xml") }.getOrNull() ?: Preview.Text(text, "xml")
    }

    /** A DOM parser that neither fetches the DTD (Apple property lists name one by URL) nor resolves entities. */
    internal fun secureBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }

    private fun prettyXml(doc: org.w3c.dom.Document): String {
        // Whitespace only text nodes would double the indentation: drop them first.
        fun strip(n: Node) {
            var c = n.firstChild
            while (c != null) {
                val next = c.nextSibling
                if (c.nodeType == Node.TEXT_NODE && c.textContent.isBlank() && n.childNodes.length > 1) n.removeChild(c) else strip(c)
                c = next
            }
        }
        strip(doc.documentElement)
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.INDENT, "yes")
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val out = java.io.StringWriter()
        t.transform(DOMSource(doc), StreamResult(out))
        return out.toString().trim()
    }

    private fun plistTable(value: PlistValue, name: String): Preview.Table =
        Preview.Table("Property list $name", listOf("Key", "Type", "Value"), PlistFlatten.rows(value))
}

/** Android SharedPreferences: `<map>` with typed children. */
object SharedPrefs {
    private val TYPES = setOf("string", "int", "long", "float", "double", "boolean", "set")

    /** A Key / Type / Value table, or null when [map] does not look like SharedPreferences (no or untyped children). */
    fun table(map: Element): Preview.Table? {
        val rows = mutableListOf<List<String>>()
        var c = map.firstChild
        while (c != null) {
            if (c is Element) {
                if (c.tagName !in TYPES) return null
                val key = c.getAttribute("name")
                rows += when (c.tagName) {
                    "string" -> listOf(key, "string", c.textContent)
                    "set" -> {
                        val items = mutableListOf<String>()
                        var s = c.firstChild
                        while (s != null) { if (s is Element && s.tagName == "string") items += s.textContent; s = s.nextSibling }
                        listOf(key, "set", items.joinToString(", ", "[", "]"))
                    }
                    else -> listOf(key, c.tagName, c.getAttribute("value"))
                }
            }
            c = c.nextSibling
        }
        return Preview.Table("SharedPreferences (${rows.size} entries)", listOf("Key", "Type", "Value"), rows)
    }
}

/** A property list value. */
sealed class PlistValue {
    data class Dict(val entries: List<Pair<String, PlistValue>>) : PlistValue()
    data class Arr(val items: List<PlistValue>, val isSet: Boolean = false) : PlistValue()
    data class Str(val value: String) : PlistValue()
    data class Int(val value: BigInteger) : PlistValue()
    data class Real(val value: Double) : PlistValue()
    data class Bool(val value: Boolean) : PlistValue()
    /** ISO-8601 UTC text. */
    data class Date(val iso: String) : PlistValue()
    class Data(val bytes: ByteArray) : PlistValue()
    data class Uid(val value: BigInteger) : PlistValue()
    object Null : PlistValue()
}

class PlistException(message: String) : RuntimeException(message)

/** Flattens a [PlistValue] into Key / Type / Value rows; nested keys are shown as `a.b` and `a[0]`. */
object PlistFlatten {
    /** Plain decimal text (no exponent), like plutil shows it. */
    private fun realText(d: Double): String = if (d.isNaN() || d.isInfinite()) d.toString() else java.math.BigDecimal(d.toString()).toPlainString()

    fun rows(root: PlistValue): List<List<String>> {
        val out = mutableListOf<List<String>>()
        fun walk(key: String, v: PlistValue) {
            when (v) {
                is PlistValue.Dict -> { if (key.isNotEmpty()) out += listOf(key, "dict", "${v.entries.size} items"); v.entries.forEach { (k, c) -> walk(if (key.isEmpty()) k else "$key.$k", c) } }
                is PlistValue.Arr -> { if (key.isNotEmpty()) out += listOf(key, if (v.isSet) "set" else "array", "${v.items.size} items"); v.items.forEachIndexed { i, c -> walk("$key[$i]", c) } }
                is PlistValue.Str -> out += listOf(key, "string", v.value)
                is PlistValue.Int -> out += listOf(key, "integer", v.value.toString())
                is PlistValue.Real -> out += listOf(key, "real", realText(v.value))
                is PlistValue.Bool -> out += listOf(key, "bool", v.value.toString())
                is PlistValue.Date -> out += listOf(key, "date", v.iso)
                is PlistValue.Data -> out += listOf(key, "data", "${v.bytes.size} bytes: " + v.bytes.take(16).joinToString("") { "%02x".format(it) } + if (v.bytes.size > 16) "..." else "")
                is PlistValue.Uid -> out += listOf(key, "uid", v.value.toString())
                PlistValue.Null -> out += listOf(key, "null", "")
            }
        }
        walk("", root)
        return out
    }
}

object PlistParser {
    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 100_000

    /** An XML property list: [plist] is its `<plist>` element. */
    fun parseXml(plist: Element): PlistValue {
        val first = children(plist).firstOrNull() ?: throw PlistException("empty plist")
        return xmlValue(first, 0)
    }

    private fun children(e: Element): List<Element> {
        val out = mutableListOf<Element>()
        var c = e.firstChild
        while (c != null) { if (c is Element) out += c; c = c.nextSibling }
        return out
    }

    private fun xmlValue(e: Element, depth: kotlin.Int): PlistValue {
        if (depth > MAX_DEPTH) throw PlistException("nested too deeply")
        return when (e.tagName) {
            "dict" -> {
                val kids = children(e)
                val entries = mutableListOf<Pair<String, PlistValue>>()
                var i = 0
                while (i < kids.size) {
                    if (kids[i].tagName != "key" || i + 1 >= kids.size) throw PlistException("dict without key/value pairs")
                    entries += kids[i].textContent to xmlValue(kids[i + 1], depth + 1)
                    i += 2
                }
                PlistValue.Dict(entries)
            }
            "array" -> PlistValue.Arr(children(e).map { xmlValue(it, depth + 1) })
            "string" -> PlistValue.Str(e.textContent)
            "integer" -> PlistValue.Int(e.textContent.trim().toBigIntegerOrNull() ?: throw PlistException("bad integer"))
            "real" -> PlistValue.Real(e.textContent.trim().toDoubleOrNull() ?: throw PlistException("bad real"))
            "true" -> PlistValue.Bool(true)
            "false" -> PlistValue.Bool(false)
            "date" -> PlistValue.Date(e.textContent.trim())
            "data" -> PlistValue.Data(runCatching { java.util.Base64.getMimeDecoder().decode(e.textContent.trim()) }.getOrDefault(ByteArray(0)))
            else -> throw PlistException("unknown element <${e.tagName}>")
        }
    }

    /** A `bplist00` file. Throws [PlistException] for anything malformed (offsets are bounds checked, nesting is limited). */
    fun parseBinary(b: ByteArray): PlistValue {
        if (b.size < 40) throw PlistException("too short")
        val t = b.size - 32
        val offsetSize = u(b, t + 6, 1).toInt()
        val refSize = u(b, t + 7, 1).toInt()
        val count = u(b, t + 8, 8)
        val top = u(b, t + 16, 8)
        val tableAt = u(b, t + 24, 8)
        if (offsetSize !in 1..8 || refSize !in 1..8) throw PlistException("bad trailer")
        if (count <= 0 || count > b.size || top >= count || tableAt + count * offsetSize > t) throw PlistException("bad trailer")
        val offsets = LongArray(count.toInt()) { i -> u(b, (tableAt + i.toLong() * offsetSize).toInt(), offsetSize) }
        return BinaryReader(b, offsets, refSize).read(top.toInt(), 0)
    }

    private fun u(b: ByteArray, at: kotlin.Int, n: kotlin.Int): Long {
        if (at < 0 || at + n > b.size) throw PlistException("read outside the file")
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }

    private class BinaryReader(val b: ByteArray, val offsets: LongArray, val refSize: kotlin.Int) {
        /** Objects can be referenced many times, so depth alone does not bound the work: cap the total as well. */
        private var visited = 0

        fun read(index: kotlin.Int, depth: kotlin.Int): PlistValue {
            if (depth > MAX_DEPTH) throw PlistException("nested too deeply")
            if (++visited > MAX_NODES) throw PlistException("too many objects")
            if (index < 0 || index >= offsets.size) throw PlistException("bad object reference")
            val at = offsets[index].toInt()
            if (at < 8 || at >= b.size) throw PlistException("bad object offset")
            val marker = b[at].toInt() and 0xff
            val hi = marker shr 4
            val lo = marker and 0xf
            return when (hi) {
                0x0 -> when (lo) { 0x8 -> PlistValue.Bool(false); 0x9 -> PlistValue.Bool(true); 0x0 -> PlistValue.Null; else -> throw PlistException("unknown marker") }
                0x1 -> { val n = 1 shl lo; if (n > 16) throw PlistException("bad integer size"); PlistValue.Int(if (n == 16) BigInteger(1, bytes(at + 1, 16)) else if (n == 8) BigInteger.valueOf(u(b, at + 1, 8)) else BigInteger.valueOf(u(b, at + 1, n))) }
                0x2 -> PlistValue.Real(when (lo) { 2 -> java.lang.Float.intBitsToFloat(u(b, at + 1, 4).toInt()).toDouble(); 3 -> java.lang.Double.longBitsToDouble(u(b, at + 1, 8)); else -> throw PlistException("bad real size") })
                0x3 -> PlistValue.Date(Instant.parse("2001-01-01T00:00:00Z").plusMillis((java.lang.Double.longBitsToDouble(u(b, at + 1, 8)) * 1000).toLong()).toString())
                0x4 -> { val (n, s) = length(at, lo); PlistValue.Data(bytes(s, n)) }
                0x5 -> { val (n, s) = length(at, lo); PlistValue.Str(String(bytes(s, n), Charsets.ISO_8859_1)) }
                0x6 -> { val (n, s) = length(at, lo); PlistValue.Str(String(bytes(s, n * 2), Charsets.UTF_16BE)) }
                0x8 -> PlistValue.Uid(BigInteger(1, bytes(at + 1, lo + 1)))
                0xA, 0xC -> { val (n, s) = length(at, lo); PlistValue.Arr((0 until n).map { read(ref(s, it), depth + 1) }, isSet = hi == 0xC) }
                0xD -> {
                    val (n, s) = length(at, lo)
                    PlistValue.Dict((0 until n).map { i ->
                        val key = read(ref(s, i), depth + 1) as? PlistValue.Str ?: throw PlistException("non string dictionary key")
                        key.value to read(ref(s, n + i), depth + 1)
                    })
                }
                else -> throw PlistException("unknown marker 0x%02x".format(marker))
            }
        }

        private fun ref(start: kotlin.Int, i: kotlin.Int) = u(b, start + i * refSize, refSize).toInt()

        private fun bytes(at: kotlin.Int, n: kotlin.Int): ByteArray {
            if (n < 0 || at < 0 || at.toLong() + n > b.size) throw PlistException("read outside the file")
            return b.copyOfRange(at, at + n)
        }

        /** The element count of a marker at [at] and where its payload starts. */
        private fun length(at: kotlin.Int, lo: kotlin.Int): Pair<kotlin.Int, kotlin.Int> {
            if (lo != 0xf) return lo to at + 1
            val intMarker = b[at + 1].toInt() and 0xff
            if (intMarker shr 4 != 1) throw PlistException("bad length")
            val n = 1 shl (intMarker and 0xf)
            val len = u(b, at + 2, n)
            if (n > 8 || len < 0 || len > b.size) throw PlistException("bad length")
            return len.toInt() to at + 2 + n
        }
    }
}
