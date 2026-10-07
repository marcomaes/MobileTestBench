package io.github.marcomaes.mobiletestbench.core.webview

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.github.marcomaes.mobiletestbench.core.MobilecliJson

/** A locator for a DOM element. [domCount] is the number of matches in the loaded (possibly capped) DOM; the live count from the page replaces it when known. */
data class WebLocator(val kind: String, val value: String, val domCount: Int)

/** CSS selector and XPath generation with uniqueness checks against a parsed [DomPage]. */
object WebLocators {

    /** Attributes meant for tests and stable identification, in order of preference. */
    private val STABLE_ATTRIBUTES = listOf("data-testid", "data-test-id", "data-test", "data-cy", "data-qa", "name", "aria-label", "placeholder", "title", "alt")

    private val IDENT = Regex("^-?[_a-zA-Z][_a-zA-Z0-9-]*$")

    private class Step(val tag: String?, val id: String? = null, val classes: List<String> = emptyList(), val attr: Pair<String, String>? = null, val nth: Int? = null) {
        fun render(): String {
            val sb = StringBuilder(tag.orEmpty())
            if (id != null) sb.append(if (IDENT.matches(id)) "#$id" else "[id=${quote(id)}]")
            classes.forEach { sb.append('.').append(it) }
            attr?.let { sb.append('[').append(it.first).append('=').append(quote(it.second)).append(']') }
            nth?.let { sb.append(":nth-of-type($it)") }
            return sb.toString()
        }

        fun matches(n: DomNode): Boolean =
            (tag == null || n.tag == tag) && (id == null || n.id == id) && n.classes.containsAll(classes) &&
                (attr == null || n.attributes[attr.first] == attr.second) && (nth == null || nthOfType(n) == nth)
    }

    fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** An XPath string literal; uses concat() when the text holds both kinds of quote. */
    fun xpathLiteral(value: String): String = when {
        !value.contains('\'') -> "'$value'"
        !value.contains('"') -> "\"$value\""
        else -> "concat(" + value.split("'").joinToString(", \"'\", ") { "'$it'" } + ")"
    }

    private fun nthOfType(n: DomNode): Int = n.parent?.children?.takeWhile { it !== n }?.count { it.tag == n.tag }?.plus(1) ?: 1

    private fun sameTagSiblings(n: DomNode): Int = n.parent?.children?.count { it.tag == n.tag } ?: 1

    /** Matches [steps] as a child chain; the first step may sit anywhere in the tree. */
    private fun chainMatches(n: DomNode, steps: List<Step>, i: Int): Boolean {
        if (!steps[i].matches(n)) return false
        if (i == 0) return true
        val p = n.parent ?: return false
        return chainMatches(p, steps, i - 1)
    }

    private fun countCss(root: DomNode, steps: List<Step>): Int = root.descendants().count { chainMatches(it, steps, steps.size - 1) }

    fun cssSelector(node: DomNode, root: DomNode): WebLocator {
        fun unique(steps: List<Step>) = countCss(root, steps) == 1
        fun result(steps: List<Step>) = WebLocator("CSS", steps.joinToString(" > ") { it.render() }, countCss(root, steps))
        node.id?.let { id -> Step(null, id).let { if (unique(listOf(it))) return result(listOf(it)) } }
        for (name in STABLE_ATTRIBUTES) {
            val v = node.attributes[name]?.takeIf { it.isNotBlank() } ?: continue
            val s = Step(node.tag, attr = name to v)
            if (unique(listOf(s))) return result(listOf(s))
        }
        val cls = node.classes.filter { IDENT.matches(it) }
        if (cls.isNotEmpty()) {
            val s = Step(node.tag, classes = cls)
            if (unique(listOf(s))) return result(listOf(s))
        }
        // Path: tag:nth-of-type steps from the element up until the selector is unique, stopping at an ancestor with a unique id.
        val steps = ArrayDeque<Step>()
        var cur: DomNode? = node
        while (cur != null) {
            val anchor = cur !== node && cur.id != null && unique(listOf(Step(null, cur.id)))
            if (anchor) { steps.addFirst(Step(null, cur!!.id)); break }
            val ownClasses = if (cur === node) emptyList() else cur.classes.filter { IDENT.matches(it) }
            steps.addFirst(
                if (ownClasses.isNotEmpty()) Step(cur.tag, classes = ownClasses)
                else Step(cur.tag, nth = if (sameTagSiblings(cur) > 1) nthOfType(cur) else null),
            )
            // At least element plus parent, so a bare "button" that happens to be unique today is not the answer.
            if ((steps.size >= 2 || cur.parent == null) && unique(steps.toList())) break
            cur = cur.parent
        }
        return result(steps.toList())
    }

    /** True for `<svg>`/`<math>` and everything below: those elements live in another namespace, so a plain tag name in XPath never matches them. */
    private fun foreign(n: DomNode): Boolean = generateSequence(n) { it.parent }.any { it.tag == "svg" || it.tag == "math" }

    private fun xpathTag(n: DomNode): String = if (foreign(n)) "*[local-name()='${n.tag}']" else n.tag

    fun xpath(node: DomNode, root: DomNode): WebLocator {
        fun count(tag: String, attr: String, v: String) = root.descendants().count { it.tag == tag && it.attributes[attr] == v }
        node.id?.let { if (root.descendants().count { n -> n.id == it } == 1) return WebLocator("XPath", "//${xpathTag(node)}[@id=${xpathLiteral(it)}]", 1) }
        for (name in STABLE_ATTRIBUTES) {
            val v = node.attributes[name]?.takeIf { it.isNotBlank() } ?: continue
            if (count(node.tag, name, v) == 1) return WebLocator("XPath", "//${xpathTag(node)}[@$name=${xpathLiteral(v)}]", 1)
        }
        // Path from the nearest ancestor with a unique id, else from <html>; an index only where same-tag siblings exist.
        val parts = ArrayDeque<String>()
        var cur: DomNode? = node
        var prefix = ""
        while (cur != null) {
            val cid = cur.id
            if (cur !== node && cid != null && root.descendants().count { it.id == cid } == 1) {
                prefix = "//*[@id=${xpathLiteral(cid)}]"
                break
            }
            parts.addFirst(xpathTag(cur) + if (sameTagSiblings(cur) > 1) "[${nthOfType(cur)}]" else "")
            cur = cur.parent
        }
        return WebLocator("XPath", prefix + "/" + parts.joinToString("/"), 1)
    }
}

/** The page's own answer for the selected element: match counts of both locators and the element's current rect. */
data class LiveCheck(val cssCount: Int?, val cssError: String?, val xpathCount: Int?, val xpathError: String?, val rect: DoubleArray?, val viewportWidth: Double) {
    companion object {
        private val gson = GsonBuilder().disableHtmlEscaping().create()

        /**
         * One script for the selected element: `querySelectorAll(css).length`, `document.evaluate(xpath)` snapshot length, and the rect of the
         * element found by following [path] (child indexes from `<html>`, the same as [DomNode.path]). Returns a JSON string.
         */
        fun script(css: String, xpath: String, path: List<Int>): String =
            "(function(){var o={};try{o.css=document.querySelectorAll(${gson.toJson(css)}).length}catch(e){o.cssError=String(e&&e.message||e)}" +
                "try{o.xpath=document.evaluate(${gson.toJson(xpath)},document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null).snapshotLength}catch(e){o.xpathError=String(e&&e.message||e)}" +
                "var p=${path.joinToString(",", "[", "]")},el=document.documentElement;for(var i=0;i<p.length&&el;i++){el=el.children[p[i]]}" +
                "if(el){var r=el.getBoundingClientRect();o.rect=[r.x,r.y,r.width,r.height]}o.vw=document.documentElement.getBoundingClientRect().width||innerWidth;return JSON.stringify(o)})()"

        fun parse(stdout: String): LiveCheck {
            val data = MobilecliJson.data(stdout) ?: error("The web view returned nothing")
            val o = (if (data.isJsonPrimitive) JsonParser.parseString(data.asString) else data).asJsonObject
            fun int(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asInt
            fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            val rect = o.getAsJsonArray("rect")?.map { it.asDouble }?.takeIf { it.size == 4 }?.toDoubleArray()
            return LiveCheck(int("css"), str("cssError")?.let { WebViews.cleanError(it) }, int("xpath"), str("xpathError")?.let { WebViews.cleanError(it) }, rect, o.get("vw")?.asDouble ?: 0.0)
        }
    }
}

/** Text for the match count next to a locator: "1 match (unique)", "3 matches", "no match". */
fun matchText(count: Int?, error: String? = null): String = when {
    error != null -> "invalid: $error"
    count == null -> "not checked"
    count == 0 -> "no match"
    count == 1 -> "1 match (unique)"
    else -> "$count matches"
}
