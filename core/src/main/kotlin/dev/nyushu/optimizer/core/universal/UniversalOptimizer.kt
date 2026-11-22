package dev.nyushu.optimizer.core.universal

import dev.nyushu.optimizer.core.android.AndroidAnalysis
import dev.nyushu.optimizer.core.android.AndroidGameOptimizer
import dev.nyushu.optimizer.core.android.InstalledGame
import dev.nyushu.optimizer.core.android.SystemCapabilities
import dev.nyushu.optimizer.core.device.DeviceClassifier
import dev.nyushu.optimizer.core.device.ThermalPolicy
import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.DeviceTier
import dev.nyushu.optimizer.core.model.OptimizationMode
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.profile.GameKind
import dev.nyushu.optimizer.core.profile.GameProfile
import dev.nyushu.optimizer.core.profile.HistoryEntry
import dev.nyushu.optimizer.core.profile.ProfileManager
import dev.nyushu.optimizer.core.switch.ApplyResult
import dev.nyushu.optimizer.core.switch.GameRef
import dev.nyushu.optimizer.core.switch.SwitchOptimizer
import dev.nyushu.optimizer.core.switch.SwitchPlan

sealed interface GameTarget {
    val id: String
    val name: String
    val kind: GameKind

    data class Switch(val game: GameRef) : GameTarget {
        override val id get() = game.titleId.uppercase()
        override val name get() = game.name ?: game.titleId
        override val kind get() = GameKind.SWITCH
    }

    data class Android(val game: InstalledGame) : GameTarget {
        override val id get() = game.packageName
        override val name get() = game.label
        override val kind get() = GameKind.ANDROID
    }
}

enum class PipelineStep(val title: String) {
    IDENTIFY("Identify game"),
    HARDWARE("Analyze hardware"),
    SOFTWARE("Analyze software"),
    THERMAL("Analyze thermal conditions"),
    READ_PROFILE("Read existing profile"),
    SUPPORTED_CONFIG("Analyze supported configuration"),
    SELECT("Select optimization profile"),
    VALIDATE("Validate changes"),
    SAVE("Save profile / apply"),
    MONITOR("Monitor performance"),
}

enum class StepStatus { OK, WARN, NOT_AVAILABLE, SKIPPED, FAILED, PENDING }
data class StepResult(val step: PipelineStep, val status: StepStatus, val detail: String)

enum class Outcome {
    /** Switch config written, read back and verified. */
    APPLIED_AND_VERIFIED,
    /** Android game: a profile with recommendations was saved. No game setting was changed (impossible without privileged APIs). */
    PROFILE_SAVED_RECOMMENDATIONS_ONLY,
    NO_CHANGE,
    BLOCKED,
    FAILED,
}

data class UniversalPlan(
    val target: GameTarget,
    val requested: OptimizationMode,
    val effective: OptimizationMode,
    val recommended: OptimizationMode,
    val existingProfile: GameProfile?,
    val switchPlan: SwitchPlan?,
    val androidAnalysis: AndroidAnalysis?,
    val steps: List<StepResult>,
    val validationErrors: List<String>,
)

data class UniversalResult(val plan: UniversalPlan, val steps: List<StepResult>, val outcome: Outcome, val message: String)

/** Central engine: routes a game to the Switch or Android optimizer and runs the 10-step pipeline. */
class UniversalOptimizer(
    private val switch: SwitchOptimizer,
    private val android: AndroidGameOptimizer,
    private val profiles: ProfileManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun recommendMode(d: DeviceSnapshot): OptimizationMode {
        val t = ThermalPolicy.evaluate(OptimizationMode.PERFORMANCE, d.thermal, d.batteryTempC)
        if (t.effective == OptimizationMode.COOL) return OptimizationMode.COOL
        if (t.effective == OptimizationMode.BALANCED) return OptimizationMode.BALANCED
        return if (DeviceClassifier.classify(d).tier.rank <= DeviceTier.MID.rank) OptimizationMode.PERFORMANCE else OptimizationMode.BALANCED
    }

    fun preview(
        target: GameTarget, d: DeviceSnapshot, mode: OptimizationMode,
        caps: SystemCapabilities, custom: Map<String, String> = emptyMap(),
    ): UniversalPlan {
        val steps = mutableListOf<StepResult>()
        steps += StepResult(PipelineStep.IDENTIFY, StepStatus.OK,
            "${target.kind}: ${target.name} (${target.id})")

        val tier = DeviceClassifier.classify(d)
        steps += StepResult(PipelineStep.HARDWARE,
            if (d.gpuRenderer is Reading.NotAvailable) StepStatus.WARN else StepStatus.OK,
            "Tier ${tier.tier} — ${tier.basis}; RAM ${d.ramTotalMb} MB; ${d.cpuCoreCount} cores")
        steps += StepResult(PipelineStep.SOFTWARE, StepStatus.OK,
            "Android ${d.androidRelease} (API ${d.sdkInt}), kernel ${d.kernel}, Vulkan ${d.vulkanApiVersion.display()}")
        val thermal = ThermalPolicy.evaluate(mode, d.thermal, d.batteryTempC)
        steps += StepResult(PipelineStep.THERMAL,
            if (d.thermal is Reading.NotAvailable) StepStatus.NOT_AVAILABLE else if (thermal.downgraded) StepStatus.WARN else StepStatus.OK,
            if (thermal.messages.isEmpty()) "Thermal status ${d.thermal.display()}, battery ${d.batteryTempC.display("°C")}" else thermal.messages.joinToString("; "))

        val existing = profiles.get(target.kind, target.id)
        steps += StepResult(PipelineStep.READ_PROFILE, StepStatus.OK,
            existing?.let { "Saved profile: ${it.mode}" } ?: "No saved profile yet")

        val effectiveCustom = if (mode == OptimizationMode.CUSTOM && custom.isEmpty()) existing?.customSettings.orEmpty() else custom
        var switchPlan: SwitchPlan? = null
        var androidAnalysis: AndroidAnalysis? = null
        val errors = mutableListOf<String>()
        when (target) {
            is GameTarget.Switch -> {
                switchPlan = switch.plan(target.game, d, mode, effectiveCustom)
                errors += switchPlan.validation.errors
                steps += StepResult(PipelineStep.SUPPORTED_CONFIG, StepStatus.OK,
                    "${switchPlan.changes.size} setting(s) to change, ${switchPlan.alreadySet.size} already set; compatibility ${switchPlan.compatibility.status}")
            }
            is GameTarget.Android -> {
                androidAnalysis = android.analyze(target.game, d, mode, caps)
                steps += StepResult(PipelineStep.SUPPORTED_CONFIG, StepStatus.OK,
                    "${androidAnalysis.recommendations.size} recommendation(s); no setting of the game can be changed by this app")
            }
        }
        val effective = switchPlan?.effective ?: androidAnalysis?.effective ?: mode
        steps += StepResult(PipelineStep.SELECT, StepStatus.OK, "Requested $mode → effective $effective")
        steps += StepResult(PipelineStep.VALIDATE, if (errors.isEmpty()) StepStatus.OK else StepStatus.FAILED,
            if (errors.isEmpty()) "Plan is valid" else errors.joinToString("; "))
        steps += StepResult(PipelineStep.SAVE, StepStatus.PENDING, "Waiting for confirmation")
        steps += StepResult(PipelineStep.MONITOR, StepStatus.PENDING, "Starts after apply")
        return UniversalPlan(target, mode, effective, recommendMode(d), existing, switchPlan, androidAnalysis, steps, errors)
    }

    /**
     * Executes the plan. Success is reported only for what was verified:
     * Switch → file written AND read back; Android → profile saved, with no game setting changed.
     */
    fun execute(plan: UniversalPlan, emulatorClosedConfirmed: Boolean, monitorStep: StepResult): UniversalResult {
        val base = plan.steps.filter { it.step != PipelineStep.SAVE && it.step != PipelineStep.MONITOR }
        fun finish(save: StepResult, outcome: Outcome, msg: String) =
            UniversalResult(plan, base + save + monitorStep, outcome, msg)

        if (plan.validationErrors.isNotEmpty())
            return finish(StepResult(PipelineStep.SAVE, StepStatus.FAILED, "Not applied"), Outcome.BLOCKED, plan.validationErrors.joinToString("; "))

        val t = plan.target
        val now = clock()
        return when (t) {
            is GameTarget.Switch -> {
                if (!emulatorClosedConfirmed)
                    return finish(StepResult(PipelineStep.SAVE, StepStatus.SKIPPED, "Not applied"), Outcome.BLOCKED,
                        "Close Nyushu completely first: it rewrites this file when a game starts or settings are saved.")
                when (val r = switch.apply(plan.switchPlan!!)) {
                    is ApplyResult.Applied -> {
                        saveProfile(plan, now, "Applied ${r.changes} setting(s)", true)
                        finish(StepResult(PipelineStep.SAVE, StepStatus.OK, "Wrote and verified ${r.changes} setting(s) in ${r.path}"),
                            Outcome.APPLIED_AND_VERIFIED, "Config written and read back successfully. Effect inside the emulator is only visible when the game is started.")
                    }
                    is ApplyResult.NoChange -> {
                        saveProfile(plan, now, "No change needed", true)
                        finish(StepResult(PipelineStep.SAVE, StepStatus.OK, "Config already matches"), Outcome.NO_CHANGE, "Nothing to change.")
                    }
                    is ApplyResult.Failed -> {
                        saveHistoryOnly(plan, now, "Failed: ${r.reason}", false)
                        finish(StepResult(PipelineStep.SAVE, StepStatus.FAILED, r.reason + if (r.rolledBack) " (rolled back)" else " (ROLLBACK FAILED)"),
                            Outcome.FAILED, r.reason)
                    }
                    is ApplyResult.Blocked -> finish(StepResult(PipelineStep.SAVE, StepStatus.FAILED, r.reason), Outcome.BLOCKED, r.reason)
                }
            }
            is GameTarget.Android -> {
                saveProfile(plan, now, "Saved recommendations (${plan.androidAnalysis!!.recommendations.size}); no game setting changed", true)
                finish(StepResult(PipelineStep.SAVE, StepStatus.OK, "Profile saved. Nothing inside the game was changed."),
                    Outcome.PROFILE_SAVED_RECOMMENDATIONS_ONLY,
                    "Profile saved. Follow the recommendations manually; this app cannot change another game's settings.")
            }
        }
    }

    private fun saveProfile(plan: UniversalPlan, now: Long, summary: String, ok: Boolean) {
        val t = plan.target
        val cur = plan.existingProfile ?: GameProfile(t.id, t.kind, t.name, plan.requested)
        val custom = plan.switchPlan?.let { sp ->
            if (plan.requested == OptimizationMode.CUSTOM) (sp.changes + sp.alreadySet).associate { it.key to it.after } else cur.customSettings
        } ?: cur.customSettings
        profiles.save(cur.copy(
            name = t.name, mode = plan.requested, customSettings = custom, recommendedMode = plan.recommended, updatedAtMs = now,
            history = cur.history + HistoryEntry(now, plan.requested.name, summary, ok)))
    }

    private fun saveHistoryOnly(plan: UniversalPlan, now: Long, summary: String, ok: Boolean) {
        profiles.appendHistory(plan.target.kind, plan.target.id, plan.target.name, HistoryEntry(now, plan.requested.name, summary, ok))
    }
}
