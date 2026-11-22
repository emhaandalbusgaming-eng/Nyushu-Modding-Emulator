package dev.nyushu.optimizer.core.ini

/**
 * Minimal, lossless INI model matching the files written by the yuzu-family emulators (SimpleIni):
 * `[Section]` headers, `key=value` lines (keys may contain backslashes), `;`/`#` comments.
 * Unknown keys, comments and ordering are preserved on round-trip.
 */
class IniDocument private constructor(private val lines: MutableList<Line>) {

    sealed class Line {
        data class Raw(val text: String) : Line()
        data class Section(val name: String) : Line()
        data class Entry(val key: String, var value: String) : Line()
    }

    fun sections(): List<String> = lines.filterIsInstance<Line.Section>().map { it.name }.distinct()

    fun get(section: String, key: String): String? {
        var current: String? = null
        var found: String? = null
        for (l in lines) when (l) {
            is Line.Section -> current = l.name
            is Line.Entry -> if (current == section && l.key == key) found = l.value
            is Line.Raw -> {}
        }
        return found
    }

    fun set(section: String, key: String, value: String) {
        var current: String? = null
        var sectionEnd = -1
        for ((i, l) in lines.withIndex()) {
            when (l) {
                is Line.Section -> {
                    current = l.name
                    if (l.name == section) sectionEnd = i
                }
                is Line.Entry -> if (current == section) {
                    sectionEnd = i
                    if (l.key == key) { l.value = value; return }
                }
                is Line.Raw -> if (current == section && l.text.isNotBlank()) sectionEnd = i
            }
        }
        if (sectionEnd >= 0) {
            lines.add(sectionEnd + 1, Line.Entry(key, value))
        } else {
            if (lines.isNotEmpty() && (lines.last() as? Line.Raw)?.text?.isBlank() != true) lines.add(Line.Raw(""))
            lines.add(Line.Section(section))
            lines.add(Line.Entry(key, value))
        }
    }

    fun remove(section: String, key: String): Boolean {
        var current: String? = null
        val it = lines.iterator()
        var removed = false
        while (it.hasNext()) {
            when (val l = it.next()) {
                is Line.Section -> current = l.name
                is Line.Entry -> if (current == section && l.key == key) { it.remove(); removed = true }
                is Line.Raw -> {}
            }
        }
        return removed
    }

    fun entries(section: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var current: String? = null
        for (l in lines) when (l) {
            is Line.Section -> current = l.name
            is Line.Entry -> if (current == section) out[l.key] = l.value
            is Line.Raw -> {}
        }
        return out
    }

    fun serialize(): String = lines.joinToString("\n") {
        when (it) {
            is Line.Raw -> it.text
            is Line.Section -> "[${it.name}]"
            is Line.Entry -> "${it.key}=${it.value}"
        }
    }.let { if (it.isEmpty()) it else it + "\n" }

    companion object {
        fun empty() = IniDocument(mutableListOf())

        fun parse(text: String): IniDocument {
            val out = mutableListOf<Line>()
            val rawLines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val effective = if (rawLines.isNotEmpty() && rawLines.last().isEmpty()) rawLines.dropLast(1) else rawLines
            for (raw in effective) {
                val t = raw.trim()
                when {
                    t.startsWith("[") && t.endsWith("]") && t.length >= 2 -> out.add(Line.Section(t.substring(1, t.length - 1)))
                    t.isEmpty() || t.startsWith(";") || t.startsWith("#") -> out.add(Line.Raw(raw))
                    else -> {
                        val eq = raw.indexOf('=')
                        if (eq <= 0) out.add(Line.Raw(raw))
                        else out.add(Line.Entry(raw.substring(0, eq).trim(), raw.substring(eq + 1).trim()))
                    }
                }
            }
            return IniDocument(out)
        }
    }
}
