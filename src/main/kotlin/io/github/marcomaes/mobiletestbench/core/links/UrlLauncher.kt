package io.github.marcomaes.mobiletestbench.core.links

/** Pure helpers for the URL / deep link launcher: validation, the mobilecli arguments, error wording and the history list. */
object UrlLauncher {
    const val MAX_HISTORY = 20

    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")

    /** The cleaned value, or an error text. A value must be one line without whitespace and start with a scheme (`https:`, `myapp:`). */
    sealed class Validation {
        data class Ok(val url: String) : Validation()
        data class Invalid(val reason: String) : Validation()
    }

    fun validate(input: String): Validation {
        val value = input.trim()
        return when {
            value.isEmpty() -> Validation.Invalid("Enter a URL or deep link")
            value.any { it.isWhitespace() } ->
                Validation.Invalid(if (value.any { it == '\n' || it == '\r' }) "Use a single line" else "A URL cannot contain spaces (percent-encode them as %20)")
            !scheme.matches(value) -> Validation.Invalid("Start with a scheme, for example https:// or myapp://")
            else -> Validation.Ok(value)
        }
    }

    /** Arguments for mobilecli (the device is added by the caller). */
    fun args(url: String): List<String> = listOf("url", url)

    /**
     * A short, readable reason from mobilecli's error text. Android errors are multi-line and keep their own `Error:` line;
     * iOS errors are only "exit status N" (115: no handler, 206: malformed).
     */
    fun friendlyError(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.firstOrNull { it.startsWith("Error:") }?.let { return it.removePrefix("Error:").trim() }
        val status = Regex("exit status (\\d+)").find(raw)?.groupValues?.get(1)
        if (lines.size <= 1 || lines.first().contains("exit status")) {
            when (status) {
                "115" -> return "No app on the device handles this URL scheme"
                "206" -> return "Not a valid URL (is the scheme missing?)"
            }
        }
        return lines.firstOrNull() ?: "Could not open the URL"
    }

    /** [history] with [url] moved to the front: distinct, newest first, at most [MAX_HISTORY]. */
    fun addToHistory(history: List<String>, url: String): List<String> =
        (listOf(url) + history.filter { it != url }).take(MAX_HISTORY)
}
