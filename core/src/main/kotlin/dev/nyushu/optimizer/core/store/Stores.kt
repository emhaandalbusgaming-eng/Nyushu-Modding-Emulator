package dev.nyushu.optimizer.core.store

import java.io.File

/** Abstraction over the emulator's user directory (SAF on Android, plain files in tests). Paths are relative, '/'-separated. */
interface ConfigStore {
    fun read(path: String): String?
    fun write(path: String, content: String)
    fun delete(path: String)
    fun list(dir: String): List<String>
}

data class BackupEntry(val id: String, val key: String, val timestampMs: Long, val content: String?)

/** Private (app-owned) backup storage. `content == null` means the file did not exist when the backup was made. */
interface BackupStore {
    fun save(key: String, content: String?, timestampMs: Long): BackupEntry
    fun latest(key: String): BackupEntry?
    fun list(key: String): List<BackupEntry>
}

class InMemoryConfigStore(val files: MutableMap<String, String> = linkedMapOf()) : ConfigStore {
    var failWrites = false
    var corruptWrites = false
    override fun read(path: String) = files[path]
    override fun write(path: String, content: String) {
        if (failWrites) throw java.io.IOException("simulated write failure")
        files[path] = if (corruptWrites) content.replace("=", "#") else content
    }
    override fun delete(path: String) { files.remove(path) }
    override fun list(dir: String): List<String> {
        val p = dir.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(p) && !it.removePrefix(p).contains('/') }.map { it.removePrefix(p) }
    }
}

class InMemoryBackupStore : BackupStore {
    private val items = mutableListOf<BackupEntry>()
    override fun save(key: String, content: String?, timestampMs: Long): BackupEntry {
        val e = BackupEntry("${key}-${timestampMs}-${items.size}", key, timestampMs, content)
        items.add(e); return e
    }
    override fun latest(key: String) = items.lastOrNull { it.key == key }
    override fun list(key: String) = items.filter { it.key == key }
}

class FileConfigStore(private val root: File) : ConfigStore {
    private fun f(path: String) = File(root, path).also { require(it.canonicalPath.startsWith(root.canonicalPath)) { "path escapes root" } }
    override fun read(path: String) = f(path).takeIf { it.isFile }?.readText()
    override fun write(path: String, content: String) { f(path).apply { parentFile?.mkdirs() }.writeText(content) }
    override fun delete(path: String) { f(path).delete() }
    override fun list(dir: String) = f(dir).list()?.sorted() ?: emptyList()
}

class FileBackupStore(private val root: File) : BackupStore {
    private fun dir(key: String) = File(root, key.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
    override fun save(key: String, content: String?, timestampMs: Long): BackupEntry {
        val id = "$timestampMs" + if (content == null) ".absent" else ".ini"
        File(dir(key), id).writeText(content ?: "")
        return BackupEntry(id, key, timestampMs, content)
    }
    override fun list(key: String): List<BackupEntry> =
        (dir(key).listFiles() ?: emptyArray()).mapNotNull { f ->
            val ts = f.name.substringBefore('.').toLongOrNull() ?: return@mapNotNull null
            BackupEntry(f.name, key, ts, if (f.name.endsWith(".absent")) null else f.readText())
        }.sortedBy { it.timestampMs }
    override fun latest(key: String) = list(key).lastOrNull()
}
