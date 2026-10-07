package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Derived
import io.github.marcomaes.mobiletestbench.core.inspector.MobilewrightLocators.Kind

/**
 * A locator made unique by a parent (any element that encloses it): `screen.getByTestId('product-item-2').getByTestId('add-to-cart')` finds the
 * "add-to-cart" inside "product-item-2" only. Mobilewright scopes a chained locator to the bounds of the parent.
 */
object ScopedLocators {

    class Scoped(val parent: Derived, val child: Derived, val parentNode: UiNode) {
        fun code(): String = chain(parent, child)
    }

    /** How many of the nearest enclosing elements are tried as a parent. */
    private const val MAX_CANDIDATES = 20

    fun chain(parent: Derived, child: Derived): String =
        MobilewrightLocators.toCode(parent) + MobilewrightLocators.toCode(child).removePrefix("screen")

    /** Elements matching [locator] that lie inside [parent] (the way a chained Mobilewright locator scopes). */
    fun within(all: List<UiNode>, parent: UiNode, locator: Derived): List<UiNode> {
        val box = parent.bounds ?: return emptyList()
        return all.filter { it !== parent && MobilewrightLocators.matches(it, locator) && it.bounds?.let(box::contains) == true }
    }

    /**
     * The closest enclosing element that has a locator of its own that is unique on the screen, and inside which
     * one of [node]'s locators is unique. "Enclosing" is by bounds, like Mobilewright's scoping: in a flattened
     * tree (React Native) a card like `product-item-4` is a sibling of its button, not its parent, and still
     * works. An enclosing element with a unique test id wins over a nearer one that is only identified by its
     * label or text, which break with another language. Null when no parent helps.
     */
    fun forNode(node: UiNode, root: UiNode): Scoped? {
        val box = node.bounds ?: return null
        val all = root.walk().filter { !it.isRoot }.toList()
        val ownLocators = MobilewrightLocators.deriveAll(node)
        if (ownLocators.isEmpty()) return null
        val enclosing = all
            .filter { it !== node && it.bounds?.let { b -> b.contains(box) && b.area > box.area } == true }
            .sortedBy { it.bounds!!.area }
            .take(MAX_CANDIDATES)
        var fallback: Scoped? = null
        for (candidate in enclosing) {
            val parentLocator = MobilewrightLocators.deriveAll(candidate)
                .firstOrNull { p -> all.count { MobilewrightLocators.matches(it, p) } == 1 } ?: continue
            val child = ownLocators.firstOrNull { c -> within(all, candidate, c).singleOrNull() === node } ?: continue
            val scoped = Scoped(parentLocator, child, candidate)
            if (parentLocator.kind == Kind.TEST_ID) return scoped
            if (fallback == null) fallback = scoped
        }
        return fallback
    }
}
