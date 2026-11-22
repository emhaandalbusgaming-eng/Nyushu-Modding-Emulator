package dev.nyushu.optimizer

import android.app.Application
import android.content.Context
import android.net.Uri
import dev.nyushu.optimizer.core.android.AndroidGameOptimizer
import dev.nyushu.optimizer.core.profile.ProfileManager
import dev.nyushu.optimizer.core.profile.ProfileStore
import dev.nyushu.optimizer.core.store.BackupStore
import dev.nyushu.optimizer.core.store.ConfigStore
import dev.nyushu.optimizer.core.store.FileBackupStore
import dev.nyushu.optimizer.core.switch.CompatibilityDb
import dev.nyushu.optimizer.core.switch.SwitchOptimizer
import dev.nyushu.optimizer.core.universal.UniversalOptimizer
import dev.nyushu.optimizer.data.*
import dev.nyushu.optimizer.monitor.MonitorHub
import java.io.File

/** Manual service locator: small enough that a DI framework would only add dependencies. */
class App : Application() {
    val settings by lazy { AppSettings(this) }
    val detection by lazy { DeviceDetection(this, settings) }
    val scanner by lazy { GameScanner(this, settings) }
    val foreground by lazy { ForegroundDetector(this) }
    val switchLibrary by lazy { SwitchLibrary(this, settings) }
    val monitor by lazy { MonitorHub(detection) }
    val backups: BackupStore by lazy { FileBackupStore(File(filesDir, "backups")) }
    val profiles: ProfileManager by lazy { ProfileManager(FileProfileStore(File(filesDir, "profiles"))) }
    private val androidOptimizer = AndroidGameOptimizer()

    fun compatDb(): CompatibilityDb {
        val f = File(filesDir, "compat.csv")
        return if (f.isFile) CompatibilityDb.parseCsv(f.readText()) else CompatibilityDb()
    }

    /** The emulator's user directory as a ConfigStore, or null if access was not granted / is revoked. */
    fun nyushuStore(): ConfigStore? {
        val uri = settings.nyushuTreeUri?.let(Uri::parse) ?: return null
        val granted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
        if (!granted) return null
        return SafConfigStore(contentResolver, SafTree(contentResolver, uri))
    }

    fun universal(store: ConfigStore): UniversalOptimizer =
        UniversalOptimizer(SwitchOptimizer(store, backups, compatDb()), androidOptimizer, profiles)

    /** Android-only analysis does not need the emulator folder; this engine instance writes nothing for Android targets. */
    fun universalForAndroid(): UniversalOptimizer = universal(object : ConfigStore {
        override fun read(path: String): String? = null
        override fun write(path: String, content: String) = throw UnsupportedOperationException("not used for Android games")
        override fun delete(path: String) = throw UnsupportedOperationException("not used for Android games")
        override fun list(dir: String): List<String> = emptyList()
    })

    companion object { fun get(ctx: Context): App = ctx.applicationContext as App }
}

class FileProfileStore(private val dir: File) : ProfileStore {
    init { dir.mkdirs() }
    private fun f(key: String) = File(dir, "$key.properties")
    override fun read(key: String) = f(key).takeIf { it.isFile }?.readText()
    override fun write(key: String, text: String) { f(key).writeText(text) }
    override fun delete(key: String) { f(key).delete() }
    override fun keys() = dir.list()?.filter { it.endsWith(".properties") }?.map { it.removeSuffix(".properties") } ?: emptyList()
}
