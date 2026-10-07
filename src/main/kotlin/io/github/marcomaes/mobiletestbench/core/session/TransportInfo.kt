package io.github.marcomaes.mobiletestbench.core.session

/** The transport a session uses plus, when it is not the preferred one, why a fallback happened. */
data class TransportInfo(val name: String, val fallbackReason: String? = null) {
    /** Plain text, one line per fact, for a status bar tooltip. */
    fun tooltip(): String = buildString {
        append("Transport: ").append(name)
        fallbackReason?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\nFallback: ").append(oneLine(it)) }
    }

    companion object {
        private const val MAX_REASON = 300

        /** First line of [text], cut to a sensible length. */
        fun oneLine(text: String): String {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            return if (line.length > MAX_REASON) line.take(MAX_REASON - 3) + "..." else line
        }
    }
}
