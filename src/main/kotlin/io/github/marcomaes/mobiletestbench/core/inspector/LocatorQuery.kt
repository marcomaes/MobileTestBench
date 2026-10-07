package io.github.marcomaes.mobiletestbench.core.inspector

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory

/**
 * Evaluates a locator typed by the user against a hierarchy, so it can be tried before it goes into a test.
 * Supported: Mobilewright `screen.getByTestId/getByRole/getByLabel/getByText(...)`, chained to scope to a parent and with
 * `.nth(n)`, `.first()`, `.last()`, XPath (starting with `/` or `(`) and Appium locators (see [AppiumLocatorQuery]).
 */
object LocatorQuery {

    /** `/hierarchy/android.widget.FrameLayout[1]/...` as generated for "xpath (absolute)". */
    private val absolutePath = Regex("""(?:/[^/\[\]\s@=()'"]+(?:\[\d+])?)+""")
    private val pathSegment = Regex("""/([^/\[\]]+)(?:\[(\d+)])?""")
    private val call = Regex("""(getBy(?:TestId|Role|Label|Text)|nth|first|last)\(""")
    private val stringLiteral = Regex("""'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"""")
    private val roleName = Regex("""name\s*:\s*('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")""")

    /** All matching nodes in document order. Throws [IllegalArgumentException] with a readable message for bad input. */
    fun find(expression: String, root: UiNode): List<UiNode> {
        val text = expression.trim()
        require(text.isNotEmpty()) { "Empty locator" }
        return when {
            AppiumLocatorQuery.looksLikeAppium(text) -> AppiumLocatorQuery.find(text, root)
            absolutePath.matches(text) || text.startsWith("/") || text.startsWith("(") -> findXPath(text, root)
            else -> findByMobilewright(text, root)
        }
    }

    /** An XPath, either the absolute `/a/b[2]` form (walked without the XPath engine) or any XPath the JDK engine accepts. */
    internal fun findXPath(text: String, root: UiNode): List<UiNode> {
        val xpath = text.trim()
        require(xpath.isNotEmpty()) { "Empty XPath" }
        return if (absolutePath.matches(xpath)) findByAbsolutePath(xpath, root) else findByXPath(xpath, root)
    }

    private const val USAGE = "Use screen.getByTestId('...'), getByRole, getByLabel, getByText (chain them to scope to a parent), an XPath starting with /, or an Appium locator such as id=..., accessibility id=... or By.id(\"...\")"

    /** `screen.getByX(...)` calls chained with `.getByX(...)` (scoped to the bounds of the previous matches), `.nth(n)`, `.first()`, `.last()`. */
    private fun findByMobilewright(text: String, root: UiNode): List<UiNode> {
        val all = root.walk().filter { !it.isRoot }.toList()
        var current: List<UiNode>? = null // null: the whole screen
        var pos = if (text.startsWith("screen.")) "screen.".length else 0
        while (pos < text.length) {
            val match = call.find(text, pos)?.takeIf { it.range.first == pos } ?: throw IllegalArgumentException(USAGE)
            val open = match.range.last
            val close = closingParenthesis(text, open) ?: throw IllegalArgumentException("Missing closing parenthesis")
            val args = text.substring(open + 1, close)
            current = when (val name = match.groupValues[1]) {
                "nth" -> listOfNotNull(current.orEmpty().getOrNull(args.trim().toIntOrNull() ?: throw IllegalArgumentException("nth() takes a number")))
                "first" -> listOfNotNull(current.orEmpty().firstOrNull())
                "last" -> listOfNotNull(current.orEmpty().lastOrNull())
                else -> {
                    val derived = derive(name.removePrefix("getBy"), args)
                    val parents = current
                    all.filter { n ->
                        MobilewrightLocators.matches(n, derived) &&
                            (parents == null || parents.any { p -> p !== n && p.bounds != null && n.bounds != null && p.bounds.contains(n.bounds) })
                    }
                }
            }
            pos = close + 1
            if (pos < text.length) {
                require(text[pos] == '.') { USAGE }
                pos++
            }
        }
        return current.orEmpty()
    }

    private fun derive(kind: String, args: String): MobilewrightLocators.Derived {
        val first = stringLiteral.find(args) ?: throw IllegalArgumentException("Expected a quoted string argument")
        val value = unescape(first)
        return when (kind) {
            "TestId" -> MobilewrightLocators.Derived(MobilewrightLocators.Kind.TEST_ID, value)
            "Role" -> MobilewrightLocators.Derived(
                MobilewrightLocators.Kind.ROLE, value,
                roleName.find(args)?.groupValues?.get(1)?.let { unescape(stringLiteral.find(it)!!) },
            )
            "Label" -> MobilewrightLocators.Derived(MobilewrightLocators.Kind.LABEL, value)
            else -> MobilewrightLocators.Derived(MobilewrightLocators.Kind.TEXT, value)
        }
    }

    /** The index of the parenthesis that closes the one at [open], skipping quoted strings. */
    internal fun closingParenthesis(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (val c = text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
                '\'', '"' -> {
                    i++
                    while (i < text.length && text[i] != c) i += if (text[i] == '\\') 2 else 1
                }
            }
            i++
        }
        return null
    }

    private fun unescape(literal: MatchResult): String =
        (literal.groups[1]?.value ?: literal.groups[2]!!.value).replace(Regex("""\\(.)""")) {
            if (it.groupValues[1] == "n") "\n" else it.groupValues[1]
        }

    /**
     * Walks the path segment by segment. The JDK XPath engine refuses expressions with more than 100 operators,
     * which a deeply nested screen exceeds, and this form needs no engine.
     */
    private fun findByAbsolutePath(path: String, root: UiNode): List<UiNode> {
        var current: List<UiNode> = emptyList()
        for ((index, match) in pathSegment.findAll(path).withIndex()) {
            val type = match.groupValues[1]
            val position = match.groupValues[2].toIntOrNull()
            current = if (index == 0) {
                listOf(root).filter { it.rawType == type && (position == null || position == 1) }
            } else {
                current.flatMap { parent ->
                    val sameType = parent.children.filter { it.rawType == type }
                    if (position == null) sameType else listOfNotNull(sameType.getOrNull(position - 1))
                }
            }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    private fun findByXPath(expression: String, root: UiNode): List<UiNode> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        document.appendChild(toElement(root, document))
        val xpath = XPathFactory.newInstance().apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }.newXPath()
        val result = try {
            xpath.evaluate(expression, document, XPathConstants.NODESET) as NodeList
        } catch (e: XPathExpressionException) {
            throw IllegalArgumentException("Invalid XPath: ${e.cause?.message ?: e.message}")
        }
        return (0 until result.length).mapNotNull { (result.item(it) as? Element)?.getUserData(NODE_KEY) as? UiNode }
    }

    private fun toElement(node: UiNode, document: org.w3c.dom.Document): Element {
        val element = document.createElement(node.rawType)
        element.setUserData(NODE_KEY, node, null)
        // Attribute names come from the device; skip any that are not valid XML names.
        node.attributes.forEach { (name, value) -> runCatching { element.setAttribute(name, value) } }
        node.children.forEach { element.appendChild(toElement(it, document)) }
        return element
    }

    private const val NODE_KEY = "uiNode"
}
