package io.github.marcomaes.mobiletestbench.core.inspector

/** Narrows the element tree of the inspector. */
data class FilterCriteria(
    /**
     * The filter field: case insensitive free text searched in type, id, label, text and value,
     * mixed with attribute terms (`clickable=true class~Button`, see [FilterQuery]). Blank matches everything.
     */
    val text: String = "",
    val visibleOnly: Boolean = false,
    val interactiveOnly: Boolean = false,
) {
    val query: FilterQuery = FilterQuery.parse(text)

    /** Terms of [text] that were not understood; they do not narrow the tree. */
    val invalidTerms: List<InvalidTerm> get() = query.invalid

    val isEmpty: Boolean get() = query.isEmpty && !visibleOnly && !interactiveOnly
}

object HierarchyFilter {

    fun matches(node: UiNode, criteria: FilterCriteria): Boolean {
        if (criteria.visibleOnly && !node.isVisible) return false
        if (criteria.interactiveOnly && !AccessibilityAudit.isInteractive(node)) return false
        val groups = criteria.query.groups
        return groups.isEmpty() || groups.any { matches(node, it) }
    }

    /** Whether [node] satisfies every part of one `OR` group. */
    fun matches(node: UiNode, group: FilterGroup): Boolean {
        if (group.terms.any { !AttributeMatcher.matches(node, it) }) return false
        if (group.notTerms.any { AttributeMatcher.matches(node, it) }) return false
        val fields = listOfNotNull(node.rawType, node.type, node.identifier, node.resourceId, node.label, node.text, node.value)
        if (group.freeText.isNotEmpty() && fields.none { it.contains(group.freeText, ignoreCase = true) }) return false
        if (group.notFree.any { word -> fields.any { it.contains(word, ignoreCase = true) } }) return false
        if (group.regexes.any { re -> fields.none { re.containsMatchIn(it) } }) return false
        if (group.notRegexes.any { re -> fields.any { re.containsMatchIn(it) } }) return false
        return true
    }

    /**
     * The nodes to show: every matching node plus all its ancestors, so a match keeps its place in the tree.
     * Returns null when the criteria are empty (show everything).
     */
    fun visibleNodes(root: UiNode, criteria: FilterCriteria): Set<UiNode>? {
        if (criteria.isEmpty) return null
        val keep = HashSet<UiNode>()
        root.walk().filter { !it.isRoot && matches(it, criteria) }.forEach { match ->
            generateSequence(match) { it.parent }.takeWhile { keep.add(it) }.count()
        }
        return keep
    }
}
