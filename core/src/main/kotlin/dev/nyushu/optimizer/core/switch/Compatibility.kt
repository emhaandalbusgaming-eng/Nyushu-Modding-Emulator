package dev.nyushu.optimizer.core.switch

enum class CompatStatus { PLAYABLE, INGAME, INTRO, MENUS, BOOTS, WONT_BOOT, UNKNOWN }

data class CompatEntry(val titleId: String, val status: CompatStatus, val note: String)

/**
 * Compatibility is NEVER inferred from device specs or game names. It is only known when the user imports a database
 * (CSV: `titleId,status[,note]`). Everything else is UNKNOWN.
 */
class CompatibilityDb(entries: List<CompatEntry> = emptyList()) {
    private val map = entries.associateBy { it.titleId }
    val size get() = map.size
    fun lookup(titleId: String): CompatEntry {
        val n = TitleId.normalize(titleId) ?: return CompatEntry(titleId, CompatStatus.UNKNOWN, "invalid title id")
        return map[n] ?: map[TitleId.baseOf(n)] ?: CompatEntry(n, CompatStatus.UNKNOWN, "no database entry")
    }

    companion object {
        fun parseCsv(text: String): CompatibilityDb {
            val out = mutableListOf<CompatEntry>()
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split(',', limit = 3).map { it.trim() }
                val id = TitleId.normalize(parts.getOrNull(0) ?: return@forEach) ?: return@forEach
                val st = parts.getOrNull(1)?.uppercase()?.replace(' ', '_')
                val status = CompatStatus.entries.firstOrNull { it.name == st } ?: return@forEach
                out += CompatEntry(id, status, parts.getOrNull(2) ?: "")
            }
            return CompatibilityDb(out)
        }
    }
}
