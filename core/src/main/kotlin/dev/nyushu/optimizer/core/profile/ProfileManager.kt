package dev.nyushu.optimizer.core.profile

import dev.nyushu.optimizer.core.model.OptimizationMode
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

enum class GameKind { SWITCH, ANDROID }

data class HistoryEntry(val timestampMs: Long, val action: String, val summary: String, val success: Boolean)

data class GameProfile(
    val id: String,              // Title ID or package name
    val kind: GameKind,
    val name: String,
    val mode: OptimizationMode,
    val customSettings: Map<String, String> = emptyMap(),
    val recommendedMode: OptimizationMode? = null,
    val updatedAtMs: Long = 0,
    val history: List<HistoryEntry> = emptyList(),
)

interface ProfileStore {
    fun read(key: String): String?
    fun write(key: String, text: String)
    fun delete(key: String)
    fun keys(): List<String>
}

class InMemoryProfileStore : ProfileStore {
    val data = linkedMapOf<String, String>()
    override fun read(key: String) = data[key]
    override fun write(key: String, text: String) { data[key] = text }
    override fun delete(key: String) { data.remove(key) }
    override fun keys() = data.keys.toList()
}

/** One profile per game (Title ID or package name), serialised as java.util.Properties text. */
class ProfileManager(private val store: ProfileStore, private val maxHistory: Int = 50) {
    private fun storeKey(kind: GameKind, id: String) = "${kind.name.lowercase()}__${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}"

    fun get(kind: GameKind, id: String): GameProfile? = store.read(storeKey(kind, id))?.let { decode(it) }

    fun save(profile: GameProfile): GameProfile {
        val p = profile.copy(history = profile.history.takeLast(maxHistory))
        store.write(storeKey(p.kind, p.id), encode(p))
        return p
    }

    fun delete(kind: GameKind, id: String) = store.delete(storeKey(kind, id))

    fun all(): List<GameProfile> = store.keys().mapNotNull { k -> store.read(k)?.let { runCatching { decode(it) }.getOrNull() } }
        .sortedByDescending { it.updatedAtMs }

    fun appendHistory(kind: GameKind, id: String, name: String, entry: HistoryEntry): GameProfile {
        val cur = get(kind, id) ?: GameProfile(id, kind, name, OptimizationMode.BALANCED)
        return save(cur.copy(history = cur.history + entry, updatedAtMs = entry.timestampMs))
    }

    private fun encode(p: GameProfile): String {
        val pr = Properties()
        pr["id"] = p.id; pr["kind"] = p.kind.name; pr["name"] = p.name; pr["mode"] = p.mode.name
        p.recommendedMode?.let { pr["recommended"] = it.name }
        pr["updatedAt"] = p.updatedAtMs.toString()
        p.customSettings.forEach { (k, v) -> pr["custom.$k"] = v }
        p.history.forEachIndexed { i, h -> pr["history.$i"] = "${h.timestampMs}|${h.success}|${h.action}|${h.summary.replace('\n', ' ')}" }
        return StringWriter().also { pr.store(it, null) }.toString()
    }

    private fun decode(text: String): GameProfile {
        val pr = Properties().also { it.load(StringReader(text)) }
        val custom = pr.stringPropertyNames().filter { it.startsWith("custom.") }.associate { it.removePrefix("custom.") to pr.getProperty(it) }
        val hist = pr.stringPropertyNames().filter { it.startsWith("history.") }
            .sortedBy { it.removePrefix("history.").toInt() }
            .mapNotNull { k ->
                val parts = pr.getProperty(k).split('|', limit = 4)
                if (parts.size < 4) null else HistoryEntry(parts[0].toLongOrNull() ?: 0, parts[2], parts[3], parts[1].toBoolean())
            }
        return GameProfile(
            pr.getProperty("id"), GameKind.valueOf(pr.getProperty("kind")), pr.getProperty("name") ?: "",
            OptimizationMode.valueOf(pr.getProperty("mode")), custom,
            pr.getProperty("recommended")?.let { OptimizationMode.valueOf(it) },
            pr.getProperty("updatedAt")?.toLongOrNull() ?: 0, hist,
        )
    }
}
