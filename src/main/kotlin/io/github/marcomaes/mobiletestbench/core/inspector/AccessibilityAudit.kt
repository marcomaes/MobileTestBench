package io.github.marcomaes.mobiletestbench.core.inspector

import io.github.marcomaes.mobiletestbench.core.Platform
import io.github.marcomaes.mobiletestbench.core.Rect
import io.github.marcomaes.mobiletestbench.core.Size
import java.awt.image.BufferedImage
import kotlin.math.hypot

data class AccessibilityIssue(
    val node: UiNode,
    val rule: Rule,
    val message: String,
    /** The measured colors, only for [Rule.LOW_CONTRAST_HINT]. */
    val contrast: ContrastEstimate? = null,
) {
    enum class Rule(val title: String) {
        MISSING_NAME("Missing name"),
        TARGET_BELOW_MINIMUM("Target below minimum"),
        TARGET_BELOW_RECOMMENDED("Target below recommended"),
        LOW_CONTRAST_HINT("Low contrast hint"),
    }
}

/** Whether the contrast rule ran, for the status line of the Accessibility tab. */
enum class ContrastStatus { OFF, NO_FRAME, ON }

class AuditResult(
    val issues: List<AccessibilityIssue>,
    val platform: Platform,
    val densityDpi: Int?,
    val contrast: ContrastStatus,
) {
    /** One line saying what could and could not be checked. */
    val statusLine: String
        get() {
            val size = when {
                platform == Platform.IOS -> "Sizes in points"
                densityDpi == null -> "Density unknown: Android size rules skipped"
                else -> "Density $densityDpi dpi (${"%.2f".format(java.util.Locale.ROOT, densityDpi / 160.0)}x)"
            }
            val colors = when (contrast) {
                ContrastStatus.ON -> "frame sampling on"
                ContrastStatus.NO_FRAME -> "no frame: contrast skipped"
                ContrastStatus.OFF -> "contrast hints off"
            }
            return "$size - $colors"
        }
}

/**
 * Cheap accessibility checks on a hierarchy, based on WCAG 2.2 (the current W3C Recommendation;
 * WCAG 3.0 is still a Working Draft). WCAG is written for the web, so a point/dp is treated as a CSS pixel.
 * Only visible, interactive elements are checked.
 *  - MISSING_NAME (SC 4.1.2 Name, Role, Value, level A): no label or text on the element or any
 *    descendant, so a screen reader has nothing to say.
 *  - TARGET_BELOW_MINIMUM (SC 2.5.8 Target Size (Minimum), level AA): smaller than 24 x 24, unless the
 *    spacing exception applies (a 24 diameter circle centered on the target touches no other target
 *    or undersized-target circle).
 *  - TARGET_BELOW_RECOMMENDED: smaller than 44 x 44 on iOS (Apple's guideline, WCAG 2.5.5 level AAA) or 48 x 48 dp
 *    on Android (Material guidance). Not an AA failure.
 *  - LOW_CONTRAST_HINT (SC 1.4.3, level AA, 4.5:1): a heuristic on the screen image, see [ContrastSampler].
 *
 * iOS bounds are points. Android bounds are pixels: the size rules need the screen density (`adb shell wm density`,
 * the hierarchy does not carry it) and are skipped without it. Android sizes are judged in dp, including the spacing circle.
 * The size rules only look at the outermost control: an element inside a clickable or checkable ancestor is part of that
 * target. Not size checked: inline text links (iOS Link, Android clickable TextView; SC 2.5.8 "Inline" exception),
 * switches, checkboxes, radio buttons, seek bars and spinners on the 48 dp rule (they draw smaller than their touch
 * area, which the dump does not show; the 24 rule still applies), disabled Android controls, Android system UI
 * (resource id `com.android.systemui:`), iOS elements inside a cell or control, and Android elements cut by the
 * screen edge (the visible part says nothing about the real size).
 *
 * Contrast: only visible, enabled elements that show text (Android `text`; iOS label or value of static text, buttons,
 * links and text fields). Large text (3:1) cannot be told from the dump, so 4.5:1 is used and the result is a hint.
 */
object AccessibilityAudit {
    const val MINIMUM_TARGET = 24
    const val RECOMMENDED_TARGET = 44
    const val RECOMMENDED_TARGET_ANDROID_DP = 48
    const val CONTRAST_TARGET = 4.5

    /** The sampled ratio is an estimate (anti-aliased text, scaling): only a clearly lower ratio is reported. */
    const val CONTRAST_MARGIN = 0.1

    private val iosInteractive = setOf("button", "switch", "slider", "textfield", "securetextfield", "searchfield", "link", "tab")

    /** Custom controls whose own frame is the touch target. */
    private val iosSizeChecked = setOf("button", "tab")

    /** Containers whose frame, not their children's, is what a finger hits. */
    private val iosTargetContainers = iosInteractive + "cell"

    private val iosTextTypes = setOf("statictext", "button", "textfield", "securetextfield", "searchfield", "link")

    /** Android controls that draw smaller than their touch area. */
    private val androidSmallDrawn = setOf("switch", "switchcompat", "checkbox", "radiobutton", "seekbar", "togglebutton", "compoundbutton", "spinner", "materialswitch", "appcompatcheckbox", "appcompatradiobutton", "appcompatseekbar", "appcompatspinner", "appcompattogglebutton")

    fun run(
        root: UiNode,
        densityDpi: Int? = null,
        frame: (() -> BufferedImage?)? = null,
        logical: Size? = null,
        contrast: Boolean = true,
    ): List<AccessibilityIssue> = analyse(root, densityDpi, frame, logical, contrast).issues

    /**
     * [densityDpi]: Android screen density, null when unknown. [frame] and [logical] (the size of the coordinate space of
     * the bounds): the screen image for the contrast rule, which is skipped without them or when [contrast] is false.
     */
    fun analyse(
        root: UiNode,
        densityDpi: Int? = null,
        frame: (() -> BufferedImage?)? = null,
        logical: Size? = null,
        contrast: Boolean = true,
    ): AuditResult {
        val all = root.walk().filter { !it.isRoot && it.isVisible }.toList()
        val interactive = all.filter { isInteractive(it) }
        val dpi = densityDpi?.takeIf { it > 0 }
        val targets = interactive.filter { isTarget(it) }
        val issues = interactive.flatMap { issuesFor(it, targets, dpi, logical) }.toMutableList()
        var status = ContrastStatus.OFF
        if (contrast) {
            val image = frame?.invoke()
            if (image == null || logical == null || logical.isEmpty) {
                status = ContrastStatus.NO_FRAME
            } else {
                status = ContrastStatus.ON
                for (node in all) {
                    contrastIssue(node, image, logical, dpi)?.let(issues::add)
                }
            }
        }
        return AuditResult(issues, root.platform, dpi, status)
    }

    private fun isTarget(node: UiNode): Boolean = when (node.platform) {
        Platform.IOS -> isOutermost(node)
        Platform.ANDROID -> node.attr("enabled") != "false" && !isSystemUi(node) && isOutermost(node)
    }

    private fun issuesFor(node: UiNode, targets: List<UiNode>, dpi: Int?, logical: Size?): List<AccessibilityIssue> = buildList {
        if (!hasName(node)) {
            add(AccessibilityIssue(node, AccessibilityIssue.Rule.MISSING_NAME,
                "No label or text: a screen reader cannot name this ${node.type} (WCAG 4.1.2, level A)."))
        }
        val b = node.bounds ?: return@buildList
        when (node.platform) {
            Platform.IOS -> {
                if (node.type.lowercase() !in iosSizeChecked || !isOutermost(node)) return@buildList
                val belowMinimum = b.width < MINIMUM_TARGET || b.height < MINIMUM_TARGET
                when {
                    belowMinimum && !hasSpacingException(node, b, targets, 1.0) -> add(AccessibilityIssue(node, AccessibilityIssue.Rule.TARGET_BELOW_MINIMUM,
                        "Touch target is ${b.width} x ${b.height} pt and crowded by other targets; WCAG 2.5.8 (AA) requires $MINIMUM_TARGET x $MINIMUM_TARGET or spacing."))
                    b.width < RECOMMENDED_TARGET || b.height < RECOMMENDED_TARGET -> add(AccessibilityIssue(node, AccessibilityIssue.Rule.TARGET_BELOW_RECOMMENDED,
                        "Touch target is ${b.width} x ${b.height} pt; Apple recommends $RECOMMENDED_TARGET x $RECOMMENDED_TARGET (WCAG 2.5.5, AAA)."))
                }
            }
            Platform.ANDROID -> {
                if (dpi == null || !isTarget(node) || isInlineLink(node)) return@buildList
                val scale = dpi / 160.0
                val wDp = DpConverter.toDp(b.width, dpi)
                val hDp = DpConverter.toDp(b.height, dpi)
                if (isCutByScreen(b, logical, wDp, hDp)) return@buildList
                val px = "${b.width} x ${b.height} px at $dpi dpi"
                val size = "${DpConverter.format(wDp)} x ${DpConverter.format(hDp)} dp"
                val belowMinimum = wDp < MINIMUM_TARGET - EPS || hDp < MINIMUM_TARGET - EPS
                when {
                    belowMinimum && !hasSpacingException(node, b, targets, scale) -> add(AccessibilityIssue(node, AccessibilityIssue.Rule.TARGET_BELOW_MINIMUM,
                        "Touch target is $size ($px) and crowded by other targets; WCAG 2.5.8 (AA) requires $MINIMUM_TARGET x $MINIMUM_TARGET dp or spacing."))
                    node.type.lowercase() in androidSmallDrawn -> Unit
                    wDp < RECOMMENDED_TARGET_ANDROID_DP - EPS || hDp < RECOMMENDED_TARGET_ANDROID_DP - EPS -> add(AccessibilityIssue(node, AccessibilityIssue.Rule.TARGET_BELOW_RECOMMENDED,
                        "Touch target is $size ($px); Material recommends $RECOMMENDED_TARGET_ANDROID_DP x $RECOMMENDED_TARGET_ANDROID_DP dp (guidance, not an AA failure)."))
                }
            }
        }
    }

    private const val EPS = 1e-6

    /**
     * A control cut by the screen edge keeps a tiny positive height in the dump (a 6 px high "DATE" header at the
     * bottom edge): its visible size says nothing, so it is not judged. Needs the display size.
     */
    private fun isCutByScreen(b: Rect, logical: Size?, wDp: Double, hDp: Double): Boolean {
        if (logical == null || logical.isEmpty) return false
        val cutX = b.x < 0 || b.x + b.width >= logical.width
        val cutY = b.y < 0 || b.y + b.height >= logical.height
        return (cutX && wDp < RECOMMENDED_TARGET_ANDROID_DP) || (cutY && hDp < RECOMMENDED_TARGET_ANDROID_DP)
    }

    private fun contrastIssue(node: UiNode, frame: BufferedImage, logical: Size, dpi: Int?): AccessibilityIssue? {
        val b = node.bounds ?: return null
        if (!showsText(node)) return null
        val estimate = ContrastSampler.estimate(frame, logical, b) ?: return null
        if (estimate.ratio >= CONTRAST_TARGET - CONTRAST_MARGIN) return null
        val ratio = "%.2f".format(java.util.Locale.ROOT, estimate.ratio)
        return AccessibilityIssue(node, AccessibilityIssue.Rule.LOW_CONTRAST_HINT,
            "Possible low contrast $ratio:1 (text ${Contrast.hex(estimate.foreground)} on ${Contrast.hex(estimate.background)}); " +
                "WCAG 1.4.3 asks 4.5:1 (3:1 for large text, which cannot be told from the dump). Estimated from the screen image, treat as a hint.",
            estimate)
    }

    /** Elements whose own text is drawn: Android `text`, iOS label or value of text-like types. Disabled ones are exempt (SC 1.4.3). */
    private fun showsText(node: UiNode): Boolean = when (node.platform) {
        Platform.ANDROID -> node.attr("enabled") != "false" && !isSystemUi(node) && !node.text.isNullOrBlank()
        Platform.IOS -> node.attr("enabled") != "false" && node.type.lowercase() in iosTextTypes &&
            !(node.label ?: node.value).isNullOrBlank()
    }

    private fun isSystemUi(node: UiNode): Boolean = node.resourceId?.startsWith("com.android.systemui:") == true

    /** A clickable TextView is a link in running text (SC 2.5.8 "Inline" exception). */
    private fun isInlineLink(node: UiNode): Boolean =
        node.type.endsWith("TextView") && !node.type.equals("CheckedTextView", ignoreCase = true)

    /**
     * SC 2.5.8 "Spacing": a circle of [MINIMUM_TARGET] diameter centered on the undersized target must not
     * intersect another target, nor the circle of another undersized target. [scale] is pixels per unit (dp on
     * Android: dpi / 160, 1 on iOS), so the circle is 24 dp wide, not 24 px.
     */
    private fun hasSpacingException(node: UiNode, bounds: Rect, targets: List<UiNode>, scale: Double): Boolean {
        val radius = MINIMUM_TARGET / 2.0
        val cx = (bounds.x + bounds.width / 2.0) / scale
        val cy = (bounds.y + bounds.height / 2.0) / scale
        return targets.filter { it !== node }.none { other ->
            val ob = other.bounds ?: return@none false
            val ox = ob.x / scale
            val oy = ob.y / scale
            val ow = ob.width / scale
            val oh = ob.height / scale
            val undersized = ow < MINIMUM_TARGET - EPS || oh < MINIMUM_TARGET - EPS
            if (undersized) {
                hypot(cx - (ox + ow / 2.0), cy - (oy + oh / 2.0)) < MINIMUM_TARGET
            } else {
                // Distance from the circle center to the rectangle.
                val dx = maxOf(ox - cx, 0.0, cx - (ox + ow))
                val dy = maxOf(oy - cy, 0.0, cy - (oy + oh))
                hypot(dx, dy) < radius
            }
        }
    }

    /** iOS: not inside a cell or control. Android: no clickable or checkable ancestor. */
    private fun isOutermost(node: UiNode): Boolean = when (node.platform) {
        Platform.IOS -> generateSequence(node.parent) { it.parent }.none { it.type.lowercase() in iosTargetContainers }
        Platform.ANDROID -> generateSequence(node.parent) { it.parent }.none { isInteractive(it) }
    }

    /** Elements a user can act on: clickable/checkable on Android, control types on iOS. */
    fun isInteractive(node: UiNode): Boolean = when (node.platform) {
        Platform.ANDROID -> node.attr("clickable") == "true" || node.attr("checkable") == "true"
        Platform.IOS -> node.type.lowercase() in iosInteractive
    }

    /** A control is named by its own label/text or by a label inside it, e.g. a button wrapping a text view. */
    private fun hasName(node: UiNode): Boolean =
        node.walk().any { !(it.label ?: it.text ?: it.value).isNullOrBlank() }
}
