package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import java.io.File

/** Saves the UI hierarchy as XML next to a screenshot: same base name, `.xml` extension. */
object HierarchyDump {

    /** The sidecar file for [image]: "a/b.png" gives "a/b.xml" (an image name without extension gets ".xml" appended). */
    fun siblingFile(image: File): File {
        val name = image.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return File(image.parentFile, "$base.xml")
    }

    /** The tree as indented XML: one element per node, attributes as given by the device, bounds as x/y/width/height. */
    fun toXml(root: UiNode): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        write(root, 0, sb)
        return sb.toString()
    }

    private fun write(node: UiNode, depth: Int, sb: StringBuilder) {
        val indent = "  ".repeat(depth)
        sb.append(indent).append('<').append(tagName(node.rawType))
        for ((key, value) in node.attributes.toSortedMap()) {
            if (!isXmlName(key)) continue
            sb.append(' ').append(key).append("=\"").append(escape(value)).append('"')
        }
        node.bounds?.let { sb.append(" x=\"${it.x}\" y=\"${it.y}\" width=\"${it.width}\" height=\"${it.height}\"") }
        if (node.children.isEmpty()) {
            sb.append("/>\n")
        } else {
            sb.append(">\n")
            node.children.forEach { write(it, depth + 1, sb) }
            sb.append(indent).append("</").append(tagName(node.rawType)).append(">\n")
        }
    }

    private fun tagName(raw: String): String = if (isXmlName(raw)) raw else "node"

    private fun isXmlName(s: String) = s.isNotEmpty() && Regex("[A-Za-z_][A-Za-z0-9_.:-]*").matches(s)

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when {
            c == '&' -> sb.append("&amp;")
            c == '<' -> sb.append("&lt;")
            c == '>' -> sb.append("&gt;")
            c == '"' -> sb.append("&quot;")
            c == '\n' -> sb.append("&#10;")
            c == '\r' -> sb.append("&#13;")
            c == '\t' -> sb.append("&#9;")
            c.code < 0x20 -> {} // not allowed in XML 1.0
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
