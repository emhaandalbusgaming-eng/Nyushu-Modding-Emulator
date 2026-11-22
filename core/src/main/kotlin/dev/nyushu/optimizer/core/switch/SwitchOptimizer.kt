package dev.nyushu.optimizer.core.switch

import dev.nyushu.optimizer.core.device.DeviceClassifier
import dev.nyushu.optimizer.core.device.ThermalAdvice
import dev.nyushu.optimizer.core.device.ThermalPolicy
import dev.nyushu.optimizer.core.device.TierResult
import dev.nyushu.optimizer.core.ini.IniDocument
import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.OptimizationMode
import dev.nyushu.optimizer.core.store.BackupStore
import dev.nyushu.optimizer.core.store.ConfigStore

data class GameRef(val titleId: String, val name: String?, val source: String)

data class ConfigChange(
    val section: String,
    val key: String,
    val before: String?,
    val beforeSource: String,
    val after: String,
    val reason: String,
)

data class Validation(val errors: List<String>, val warnings: List<String>) { val ok get() = errors.isEmpty() }

data class SwitchPlan(
    val game: GameRef,
    val requested: OptimizationMode,
    val effective: OptimizationMode,
    val tier: TierResult,
    val thermal: ThermalAdvice,
    val gpuDriver: String,
    val compatibility: CompatEntry,
    val configPath: String,
    val configExists: Boolean,
    val changes: List<ConfigChange>,
    val alreadySet: List<ConfigChange>,
    val validation: Validation,
)

sealed class ApplyResult {
    data class Applied(val path: String, val backupId: String, val changes: Int) : ApplyResult()
    data class NoChange(val path: String) : ApplyResult()
    data class Failed(val reason: String, val rolledBack: Boolean) : ApplyResult()
    data class Blocked(val reason: String) : ApplyResult()
}

/**
 * Engine A. Reads/writes only `config/custom/<TitleId>.ini` and reads `config/config.ini`, using the same
 * `key\use_global`, `key\default`, `key` layout that the emulator itself writes. Every write is backed up first,
 * read back, and rolled back if the read-back does not match.
 */
class SwitchOptimizer(
    private val store: ConfigStore,
    private val backups: BackupStore,
    private val compat: CompatibilityDb = CompatibilityDb(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        fun customPath(titleId: String) = "config/custom/${titleId.uppercase()}.ini"
        const val GLOBAL_PATH = "config/config.ini"
    }

    fun plan(game: GameRef, device: DeviceSnapshot, mode: OptimizationMode, custom: Map<String, String> = emptyMap()): SwitchPlan {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val id = TitleId.normalize(game.titleId)
        if (id == null) errors += "Title ID '${game.titleId}' is not 16 hexadecimal digits"
        val path = customPath(id ?: game.titleId)

        val tier = DeviceClassifier.classify(device)
        val thermal = ThermalPolicy.evaluate(mode, device.thermal, device.batteryTempC)
        warnings += thermal.messages
        if (id != null && TitleId.kindOf(id) == TitleKind.OTHER) warnings += "Title ID does not look like a retail application id (homebrew?)"
        if (id != null && TitleId.kindOf(id) == TitleKind.UPDATE) warnings += "This is an update id; the emulator names the per-game file after the base game's Program ID"
        if (id != null && TitleId.kindOf(id) == TitleKind.DLC) warnings += "This looks like a DLC id, not a game"
        if (device.gpuRenderer.orNull() == null) warnings += "GPU renderer NOT AVAILABLE: profile chosen from RAM/CPU only"

        val desired: Map<String, SwitchProfiles.Rule> =
            if (mode == OptimizationMode.CUSTOM) {
                if (custom.isEmpty()) errors += "Custom mode needs at least one setting"
                custom.mapValues { SwitchProfiles.Rule(it.value, "Chosen by user") }
            } else SwitchProfiles.desired(thermal.effective, tier.tier, device.ramTotalMb)

        // Validate every key against the verified schema
        val specs = LinkedHashMap<String, SettingSpec>()
        for ((k, rule) in desired) {
            val spec = SwitchSchema.find(k)
            if (spec == null) {
                val why = SwitchSchema.unverifiedApkOnlyKeys[k]
                errors += if (why != null) "Setting '$k' is not supported by this optimizer: $why" else "Setting '$k' is not a verified Nyushu per-game setting"
            } else if (!spec.isValid(rule.value)) {
                errors += "Value '${rule.value}' is not allowed for '$k' (allowed: ${spec.allowed.keys.joinToString()})"
            } else specs[k] = spec
        }

        val existingText = store.read(path)
        val doc = IniDocument.parse(existingText ?: "")
        val global = IniDocument.parse(store.read(GLOBAL_PATH) ?: "")
        val driverPath = global.get("GpuDriver", "driver_path")?.takeIf { it.isNotBlank() }
        if (store.read(GLOBAL_PATH) == null) warnings += "Global config.ini not readable: current values shown as unknown"

        val changes = mutableListOf<ConfigChange>()
        val already = mutableListOf<ConfigChange>()
        for ((k, spec) in specs) {
            val rule = desired.getValue(k)
            val overridden = doc.get(spec.section, k + SwitchSchema.USE_GLOBAL) == "false"
            val perGame = if (overridden) doc.get(spec.section, k) else null
            val gl = global.get(spec.section, k)
            val (before, src) = when {
                perGame != null -> perGame to "per-game"
                gl != null -> gl to "global"
                else -> null to "emulator default"
            }
            val change = ConfigChange(spec.section, k, before, src, rule.value, rule.reason)
            val isSet = overridden && perGame == rule.value && doc.get(spec.section, k + SwitchSchema.DEFAULT) == "false"
            if (isSet) already += change else changes += change
        }

        return SwitchPlan(
            game, mode, thermal.effective.takeIf { mode != OptimizationMode.CUSTOM } ?: OptimizationMode.CUSTOM,
            tier, thermal, driverPath ?: "system driver (no custom driver path in config)",
            compat.lookup(game.titleId), path, existingText != null, changes, already,
            Validation(errors, warnings),
        )
    }

    fun apply(plan: SwitchPlan): ApplyResult {
        if (!plan.validation.ok) return ApplyResult.Blocked(plan.validation.errors.joinToString("; "))
        if (plan.changes.isEmpty()) return ApplyResult.NoChange(plan.configPath)

        val original = store.read(plan.configPath)
        val backup = backups.save(plan.game.titleId.uppercase(), original, clock())
        val doc = IniDocument.parse(original ?: "")
        for (c in plan.changes) {
            doc.set(c.section, c.key + SwitchSchema.USE_GLOBAL, "false")
            doc.set(c.section, c.key + SwitchSchema.DEFAULT, "false")
            doc.set(c.section, c.key, c.after)
        }
        try {
            store.write(plan.configPath, doc.serialize())
        } catch (e: Exception) {
            val rb = restore(plan.configPath, original)
            return ApplyResult.Failed("Write failed: ${e.message}", rb)
        }
        val back = IniDocument.parse(store.read(plan.configPath) ?: "")
        val bad = plan.changes.filter {
            back.get(it.section, it.key) != it.after || back.get(it.section, it.key + SwitchSchema.USE_GLOBAL) != "false"
        }
        if (bad.isNotEmpty()) {
            val rb = restore(plan.configPath, original)
            return ApplyResult.Failed("Read-back verification failed for ${bad.joinToString { it.key }}", rb)
        }
        return ApplyResult.Applied(plan.configPath, backup.id, plan.changes.size)
    }

    /** Makes every setting this optimizer can manage follow the global configuration again (backs up first). */
    fun resetToGlobal(titleId: String): ApplyResult {
        val id = TitleId.normalize(titleId) ?: return ApplyResult.Blocked("Invalid Title ID")
        val path = customPath(id)
        val original = store.read(path) ?: return ApplyResult.NoChange(path)
        val doc = IniDocument.parse(original)
        var touched = 0
        for (spec in SwitchSchema.specs) {
            if (doc.get(spec.section, spec.key + SwitchSchema.USE_GLOBAL) == "false") {
                doc.set(spec.section, spec.key + SwitchSchema.USE_GLOBAL, "true")
                doc.remove(spec.section, spec.key + SwitchSchema.DEFAULT)
                doc.remove(spec.section, spec.key)
                touched++
            }
        }
        if (touched == 0) return ApplyResult.NoChange(path)
        val backup = backups.save(id, original, clock())
        return try {
            store.write(path, doc.serialize())
            val back = IniDocument.parse(store.read(path) ?: "")
            val stillOverridden = SwitchSchema.specs.filter { back.get(it.section, it.key + SwitchSchema.USE_GLOBAL) == "false" }
            if (stillOverridden.isNotEmpty()) ApplyResult.Failed("Read-back verification failed", restore(path, original))
            else ApplyResult.Applied(path, backup.id, touched)
        } catch (e: Exception) {
            ApplyResult.Failed("Write failed: ${e.message}", restore(path, original))
        }
    }

    /** Restores the configuration as it was before the most recent apply/reset. */
    fun rollbackLast(titleId: String): ApplyResult {
        val id = TitleId.normalize(titleId) ?: return ApplyResult.Blocked("Invalid Title ID")
        val entry = backups.latest(id) ?: return ApplyResult.Blocked("No backup exists for $id")
        val ok = restore(customPath(id), entry.content)
        return if (ok) ApplyResult.Applied(customPath(id), entry.id, 0) else ApplyResult.Failed("Restore verification failed", false)
    }

    private fun restore(path: String, content: String?): Boolean = try {
        if (content == null) store.delete(path) else store.write(path, content)
        store.read(path) == content
    } catch (e: Exception) { false }
}
