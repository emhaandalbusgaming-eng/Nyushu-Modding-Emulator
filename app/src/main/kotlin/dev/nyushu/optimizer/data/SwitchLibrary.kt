package dev.nyushu.optimizer.data

import android.content.Context
import dev.nyushu.optimizer.core.store.ConfigStore
import dev.nyushu.optimizer.core.switch.TitleId
import dev.nyushu.optimizer.core.switch.TitleKind

data class SwitchEntry(
    val titleId: String,
    val name: String?,
    val kind: TitleKind,
    val sources: Set<String>,
    val hasCustomConfig: Boolean,
)

/**
 * Builds the Switch library from evidence only:
 *  1. `config/custom/<TitleId>.ini` files in Nyushu's user directory,
 *  2. `shader/<TitleId>/` cache folders (games that have been run),
 *  3. file names in a games folder you pick, when they contain `[TitleId]` (naming convention, not parsed from the file),
 *  4. games you add by hand.
 * Names are only known from (3) and (4); no name is ever guessed.
 */
class SwitchLibrary(private val ctx: Context, private val settings: AppSettings) {
    class Result(val entries: List<SwitchEntry>, val notes: List<String>)

    fun load(store: ConfigStore?): Result {
        val notes = ArrayList<String>()
        val ids = LinkedHashMap<String, MutableSet<String>>()
        val names = HashMap<String, String>()
        var custom = emptySet<String>()

        if (store != null) {
            try {
                val cfg = store.list("config/custom").mapNotNull { n -> n.removeSuffix(".ini").takeIf { n.endsWith(".ini") }?.let { TitleId.normalize(it) } }
                custom = cfg.toSet()
                cfg.forEach { ids.getOrPut(it) { linkedSetOf() }.add("per-game config") }
                store.list("shader").mapNotNull { TitleId.normalize(it) }.forEach { ids.getOrPut(it) { linkedSetOf() }.add("shader cache") }
            } catch (e: Exception) { notes += "Could not read Nyushu directory: ${e.message}" }
        } else notes += "Nyushu data folder access not granted (Settings): per-game configs and shader caches are not listed."

        settings.gamesTreeUri?.let { uri ->
            try {
                val tree = SafTree(ctx.contentResolver, android.net.Uri.parse(uri))
                for (file in tree.walkFiles(maxDepth = 3)) {
                    val id = TitleId.fromFileName(file) ?: continue
                    if (TitleId.kindOf(id) != TitleKind.BASE_GAME) continue
                    ids.getOrPut(id) { linkedSetOf() }.add("games folder")
                    names.putIfAbsent(id, file.substringBefore(" [").substringBeforeLast('.').trim().ifEmpty { id })
                }
            } catch (e: Exception) { notes += "Could not scan games folder: ${e.message}" }
        } ?: notes.add("No games folder selected (Settings): games are not discovered from file names.")

        for (m in settings.manualSwitchGames) {
            val id = TitleId.normalize(m.substringBefore('|')) ?: continue
            ids.getOrPut(id) { linkedSetOf() }.add("added by hand")
            m.substringAfter('|', "").takeIf { it.isNotBlank() }?.let { names[id] = it }
        }
        val entries = ids.map { (id, src) -> SwitchEntry(id, names[id], TitleId.kindOf(id), src, id in custom) }
            .sortedWith(compareBy({ it.name == null }, { it.name?.lowercase() ?: it.titleId }))
        return Result(entries, notes)
    }
}
