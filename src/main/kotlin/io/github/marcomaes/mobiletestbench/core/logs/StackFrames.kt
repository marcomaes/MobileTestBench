package io.github.marcomaes.mobiletestbench.core.logs

/** One source location of a stack trace line. [className] and [method] are null for a bare `(Foo.kt:34)`. */
data class StackFrame(val className: String?, val method: String?, val file: String, val line: Int) {
    /** The package of [className] as a path (`com/example`), empty when unknown or in the default package. */
    val packagePath: String
        get() {
            val outer = className?.substringBefore('$') ?: return ""
            return outer.substringBeforeLast('.', "").replace('.', '/')
        }
}

/** Finds `at com.example.Foo.bar(Foo.java:12)` style frames in log messages. */
object StackFrames {
    // at [module/]pkg.Outer$Inner.method(File.java:12); methods like <init>, lambda$foo$0 and $r8$lambda$x are fine.
    private val full = Regex("""\bat\s+(?:[\w.$-]+/)?([\w.$]+)\.([\w$<>]+)\(([\w$-]+\.(?:java|kt|kts)):(\d+)\)""")
    // (Foo.kt:34) without the rest
    private val bare = Regex("""\(([\w$-]+\.(?:java|kt|kts)):(\d+)\)""")

    /** The frame in one [line], or null. `(Native Method)`, `(Unknown Source:0)`, `(SourceFile:3)` and obfuscated forms do not match. */
    fun extract(line: String): StackFrame? {
        full.find(line)?.let { m ->
            val n = m.groupValues[4].toIntOrNull() ?: return null
            if (n <= 0) return null
            return StackFrame(m.groupValues[1], m.groupValues[2], m.groupValues[3], n)
        }
        val m = bare.find(line) ?: return null
        val n = m.groupValues[2].toIntOrNull() ?: return null
        return if (n > 0) StackFrame(null, null, m.groupValues[1], n) else null
    }

    /** The first frame of a message of one or more lines. */
    fun extractFromMessage(message: String): StackFrame? = allFrames(message).firstOrNull()

    /** Every frame of a multi line message, in order. */
    fun allFrames(message: String): List<StackFrame> = message.lineSequence().mapNotNull { extract(it) }.toList()

    /**
     * Picks the file for [frame] among [paths] (all with the frame's file name): those whose folder ends with the package path win,
     * else the first. Null when [paths] is empty.
     */
    fun choose(paths: List<String>, frame: StackFrame): String? {
        if (paths.size <= 1) return paths.firstOrNull()
        val pkg = frame.packagePath
        if (pkg.isNotEmpty()) {
            paths.firstOrNull { it.replace('\\', '/').substringBeforeLast('/', "").endsWith("/$pkg") || it.replace('\\', '/').substringBeforeLast('/', "") == pkg }
                ?.let { return it }
        }
        return paths.first()
    }
}
