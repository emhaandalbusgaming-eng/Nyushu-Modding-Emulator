package dev.nyushu.optimizer.core.android

import dev.nyushu.optimizer.core.device.DeviceClassifier
import dev.nyushu.optimizer.core.device.ThermalAdvice
import dev.nyushu.optimizer.core.device.ThermalPolicy
import dev.nyushu.optimizer.core.device.TierResult
import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.DeviceTier
import dev.nyushu.optimizer.core.model.OptimizationMode
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.model.ThermalLevel

data class InstalledGame(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int?,
    val declaresGameModeConfig: Boolean,
    val isGameCategory: Boolean,
    val installerPackage: String?,
)

/** What the companion can do for OTHER apps on this device, as detected at runtime. */
data class SystemCapabilities(
    val usageAccessGranted: Boolean,
    /** Setting another app's Game Mode needs the system-only MANAGE_GAME_MODE permission. Always false for a normal app. */
    val canSetOtherAppGameMode: Boolean = false,
    /** True only if a real source for other apps' FPS exists (none without privileged access). */
    val fpsSourceAvailable: Boolean = false,
)

enum class Applicability {
    /** The user must do it (inside the game or in Android settings). Nothing was changed by this app. */
    USER_ACTION,
    INFORMATIONAL,
    NOT_AVAILABLE,
}

enum class SystemAction { NONE, OPEN_APP_INFO, OPEN_BATTERY_SAVER, OPEN_DISPLAY_SETTINGS, OPEN_USAGE_ACCESS, OPEN_STORAGE_SETTINGS }

data class Recommendation(
    val id: String,
    val title: String,
    val detail: String,
    val applicability: Applicability,
    val action: SystemAction = SystemAction.NONE,
)

data class AndroidAnalysis(
    val game: InstalledGame,
    val requested: OptimizationMode,
    val effective: OptimizationMode,
    val tier: TierResult,
    val thermal: ThermalAdvice,
    val recommendations: List<Recommendation>,
)

/**
 * Engine B. Pure analysis: it cannot, and does not claim to, change another app's graphics settings, Game Mode or process.
 * Every output is a recommendation the user applies, or an explicit NOT_AVAILABLE.
 */
class AndroidGameOptimizer {
    fun analyze(game: InstalledGame, d: DeviceSnapshot, mode: OptimizationMode, caps: SystemCapabilities): AndroidAnalysis {
        val tier = DeviceClassifier.classify(d)
        val thermal = ThermalPolicy.evaluate(mode, d.thermal, d.batteryTempC)
        val eff = thermal.effective
        val r = mutableListOf<Recommendation>()

        for (m in thermal.messages) r += Recommendation("thermal-msg", "Thermal", m, Applicability.INFORMATIONAL)

        // In-game settings: only advice, because Android gives no API to change them from outside.
        val quality = when (eff) {
            OptimizationMode.PERFORMANCE -> if (tier.tier.rank >= DeviceTier.HIGH.rank) "High" else "Medium"
            OptimizationMode.COOL -> if (tier.tier.rank >= DeviceTier.HIGH.rank) "Medium" else "Low"
            OptimizationMode.CUSTOM -> null
            else -> if (tier.tier.rank >= DeviceTier.HIGH.rank) "Medium-High" else "Medium"
        }
        if (quality != null) r += Recommendation(
            "ingame-quality", "In-game graphics: $quality",
            "Set graphics quality to $quality inside ${game.label}. Android does not allow another app to change this; nothing was changed.",
            Applicability.USER_ACTION)
        val fpsTarget = fpsAdvice(eff, d)
        r += Recommendation("ingame-fps", "In-game frame rate: $fpsTarget",
            "Pick this frame-rate option in the game's own settings if it offers one.", Applicability.USER_ACTION, SystemAction.NONE)

        // Display
        val maxHz = d.supportedRefreshHz.maxOrNull() ?: d.displayRefreshHz
        if (eff == OptimizationMode.PERFORMANCE && maxHz > 61f)
            r += Recommendation("display-hz", "Use ${maxHz.toInt()} Hz display",
                "Display currently runs at ${d.displayRefreshHz.toInt()} Hz. Set the refresh rate in Android display settings.",
                Applicability.USER_ACTION, SystemAction.OPEN_DISPLAY_SETTINGS)
        if (eff == OptimizationMode.COOL && d.displayRefreshHz > 61f)
            r += Recommendation("display-hz", "Use 60 Hz display",
                "A lower refresh rate reduces heat and battery use.", Applicability.USER_ACTION, SystemAction.OPEN_DISPLAY_SETTINGS)

        // Game Mode
        if (game.declaresGameModeConfig)
            r += Recommendation("game-mode", "Game declares Game Mode support",
                "Choose Performance or Battery mode for ${game.label} in the system Game Dashboard / Game settings (the exact place depends on your ROM).",
                Applicability.USER_ACTION, SystemAction.OPEN_APP_INFO)
        else
            r += Recommendation("game-mode", "Game does not declare Game Mode",
                "${game.label} has no game_mode_config metadata, so the system has nothing to switch for it.", Applicability.INFORMATIONAL)
        if (!caps.canSetOtherAppGameMode)
            r += Recommendation("game-mode-api", "Game Mode API for other apps: NOT AVAILABLE",
                "Setting another app's Game Mode requires a system-level permission that normal apps cannot hold.", Applicability.NOT_AVAILABLE)

        // Resources
        if (d.ramAvailMb in 1..1499)
            r += Recommendation("ram", "Free memory is low (${d.ramAvailMb} MB)",
                "Close background apps before starting ${game.label}.", Applicability.USER_ACTION)
        val freePct = if (d.storageTotalMb > 0) d.storageFreeMb * 100.0 / d.storageTotalMb else 100.0
        if (d.storageFreeMb < 2048 || freePct < 5.0)
            r += Recommendation("storage", "Storage almost full (${d.storageFreeMb} MB free)",
                "Low storage can cause stutter while games write caches. Free some space.", Applicability.USER_ACTION, SystemAction.OPEN_STORAGE_SETTINGS)

        // Battery
        val pct = d.batteryPercent.orNull()
        val charging = d.charging.orNull()
        if (pct != null && pct < 20 && charging != true)
            r += Recommendation("battery-low", "Battery at $pct%",
                "Plug in a charger; Android may throttle performance at low battery.", Applicability.USER_ACTION, SystemAction.OPEN_BATTERY_SAVER)
        if (charging == true && eff != OptimizationMode.COOL && (d.batteryTempC.orNull() ?: 0f) >= 40f)
            r += Recommendation("charge-heat", "Charging adds heat",
                "The battery is already warm while charging. Unplug or pause charging for long sessions.", Applicability.USER_ACTION)
        if (eff == OptimizationMode.COOL)
            r += Recommendation("battery-saver", "Optional: Battery Saver",
                "Android's Battery Saver reduces performance further. Turn it on only if you want a cooler, slower device.",
                Applicability.USER_ACTION, SystemAction.OPEN_BATTERY_SAVER)

        // Monitoring limits
        if (!caps.usageAccessGranted)
            r += Recommendation("usage-access", "Active game detection needs Usage Access",
                "Grant Usage Access to let this app see which game is in the foreground. It is optional.",
                Applicability.USER_ACTION, SystemAction.OPEN_USAGE_ACCESS)
        if (!caps.fpsSourceAvailable)
            r += Recommendation("fps", "FPS of other apps: NOT AVAILABLE",
                "Android exposes no FPS counter for other apps without privileged access, so none is shown.", Applicability.NOT_AVAILABLE)
        if (game.targetSdk in 1..28)
            r += Recommendation("old-target", "Game targets Android API ${game.targetSdk}",
                "Older target SDKs can run in compatibility mode on new Android versions; expect less consistent performance.", Applicability.INFORMATIONAL)
        return AndroidAnalysis(game, mode, eff, tier, thermal, r)
    }

    private fun fpsAdvice(eff: OptimizationMode, d: DeviceSnapshot): String {
        val maxHz = d.supportedRefreshHz.maxOrNull() ?: d.displayRefreshHz
        return when (eff) {
            OptimizationMode.PERFORMANCE -> if (maxHz >= 118f) "up to 120" else if (maxHz >= 88f) "up to 90" else "60"
            OptimizationMode.COOL -> "30-60 (lower is cooler)"
            else -> "60"
        }
    }
}
