package dev.nyushu.optimizer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.AdapterView
import android.view.View
import dev.nyushu.optimizer.App
import dev.nyushu.optimizer.core.android.Applicability
import dev.nyushu.optimizer.core.android.SystemAction
import dev.nyushu.optimizer.core.android.SystemCapabilities
import dev.nyushu.optimizer.core.model.OptimizationMode
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.monitor.Metric
import dev.nyushu.optimizer.core.switch.SwitchOptimizer
import dev.nyushu.optimizer.core.switch.SwitchSchema
import dev.nyushu.optimizer.core.universal.*

/** AUTO OPTIMIZE: analyze → show current vs recommended → apply → show only verified results. */
class AutoOptimizerPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    private var target: GameTarget? = null
    private var mode: OptimizationMode = App.get(ctx).settings.defaultMode
    private val custom = LinkedHashMap<String, String>()
    private var plan: UniversalPlan? = null
    private var result: UniversalResult? = null
    private var busy: String? = null
    private var closedConfirmed = false
    private var message: String? = null

    fun open(t: GameTarget) {
        target = t; plan = null; result = null; message = null; closedConfirmed = false; custom.clear()
        App.get(ctx).profiles.get(t.kind, t.id)?.let { p -> mode = p.mode; custom.putAll(p.customSettings) }
    }

    private fun caps() = SystemCapabilities(usageAccessGranted = App.get(ctx).foreground.hasUsageAccess())

    private fun engine(): UniversalOptimizer? {
        val app = App.get(ctx)
        return when (target) {
            is GameTarget.Switch -> app.nyushuStore()?.let { app.universal(it) }
            is GameTarget.Android -> app.universalForAndroid()
            null -> null
        }
    }

    override fun refresh() {
        val t = target
        render {
            addView(Ui.title(ctx, "Auto Optimizer"))
            if (t == null) { addView(Ui.muted(ctx, "Pick a game in Switch Games or Android Games and press AUTO OPTIMIZE.")); return@render }
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, t.name, 16f, Ui.TEXT, true))
                addView(Ui.kv(ctx, if (t is GameTarget.Switch) "Title ID" else "Package", t.id))
                addView(Ui.kv(ctx, "Engine", if (t is GameTarget.Switch) "A — Switch emulator optimizer" else "B — Android game optimizer"))
                addView(Ui.text(ctx, "Mode", 12f, Ui.MUTED))
                addView(Ui.chips(ctx, OptimizationMode.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, mode.ordinal) {
                    mode = OptimizationMode.entries[it]; plan = null; result = null; refresh()
                })
                if (mode == OptimizationMode.CUSTOM && t is GameTarget.Switch) addView(customEditor())
                if (t is GameTarget.Switch) addView(CheckBox(ctx).apply {
                    text = "Nyushu is completely closed"; setTextColor(Ui.TEXT); isChecked = closedConfirmed
                    setOnCheckedChangeListener { _, v -> closedConfirmed = v }
                })
                addView(Ui.row(ctx,
                    Ui.button(ctx, "Analyze", false) { analyze() },
                    Ui.button(ctx, "Apply", true) { apply() }))
                busy?.let { addView(Ui.text(ctx, "⏳ $it", 13f, Ui.WARN)) }
                message?.let { addView(Ui.text(ctx, it, 13f, Ui.WARN)) }
            })
            plan?.let { renderPlan(this, it) }
            result?.let { renderResult(this, it) }
        }
    }

    private fun customEditor(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.muted(ctx, "Only verified per-game settings are offered. (unchanged) = keep following the global configuration."))
        for (spec in SwitchSchema.specs) {
            val labels = listOf("(unchanged)") + spec.allowed.values
            val keys = listOf<String?>(null) + spec.allowed.keys
            addView(Ui.text(ctx, "${spec.description}  [${spec.key}]", 12f, Ui.MUTED))
            addView(Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(keys.indexOf(custom[spec.key]).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val k = keys[pos]; if (k == null) custom.remove(spec.key) else custom[spec.key] = k
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }
    }

    private fun analyze() {
        val t = target ?: return
        val eng = engine()
        if (eng == null) { message = "Grant access to the Nyushu data folder in Settings first."; refresh(); return }
        busy = "Analyzing…"; message = null; result = null; refresh()
        Async.run({ eng.preview(t, App.get(ctx).detection.snapshot(), mode, caps(), custom) }) { r ->
            busy = null
            r.onSuccess { plan = it }.onFailure { message = "Analysis failed: ${it.message}" }
            refresh()
        }
    }

    private fun apply() {
        val t = target ?: return
        val eng = engine()
        if (eng == null) { message = "Grant access to the Nyushu data folder in Settings first."; refresh(); return }
        busy = "Applying… (nothing is reported as done until it is verified)"; message = null; refresh()
        Async.run({
            val d = App.get(ctx).detection.snapshot()
            val p = eng.preview(t, d, mode, caps(), custom)
            val monitorStep = StepResult(PipelineStep.MONITOR,
                if (App.get(ctx).monitor.running) StepStatus.OK else StepStatus.WARN,
                if (App.get(ctx).monitor.running) "Monitor is sampling real data" else "Open the Monitor page to start sampling")
            p to eng.execute(p, closedConfirmed, monitorStep)
        }) { r ->
            busy = null
            r.onSuccess { (p, res) -> plan = p; result = res }.onFailure { message = "Apply failed: ${it.message}" }
            refresh()
        }
    }

    private fun renderPlan(root: LinearLayout, p: UniversalPlan) = root.run {
        addView(Ui.card(ctx).apply {
            addView(Ui.text(ctx, "Pipeline", 16f, Ui.TEXT, true))
            p.steps.forEach { addView(Ui.kv(ctx, it.step.title, "${it.status}: ${it.detail}", Ui.statusColor(it.status.name))) }
        })
        p.switchPlan?.let { sp ->
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Device & game analysis", 16f, Ui.TEXT, true))
                addView(Ui.kv(ctx, "Device tier", "${sp.tier.tier} (${sp.tier.basis})"))
                addView(Ui.kv(ctx, "GPU driver", sp.gpuDriver))
                addView(Ui.kv(ctx, "Compatibility", sp.compatibility.status.name))
                addView(Ui.kv(ctx, "Mode", "requested ${sp.requested} → effective ${sp.effective}"))
                addView(Ui.kv(ctx, "Config file", sp.configPath + if (sp.configExists) "" else " (will be created)"))
                sp.validation.warnings.forEach { addView(Ui.text(ctx, "⚠ $it", 12f, Ui.WARN)) }
                sp.validation.errors.forEach { addView(Ui.text(ctx, "✖ $it", 12f, Ui.BAD)) }
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Current → recommended", 16f, Ui.TEXT, true))
                if (sp.changes.isEmpty()) addView(Ui.muted(ctx, "No changes needed."))
                sp.changes.forEach { c ->
                    val spec = SwitchSchema.find(c.key)
                    val before = c.before?.let { spec?.label(it) ?: it } ?: "unknown"
                    addView(Ui.kv(ctx, spec?.description ?: c.key, "$before (${c.beforeSource}) → ${spec?.label(c.after) ?: c.after}"))
                    addView(Ui.text(ctx, "   ${c.reason}", 11f, Ui.MUTED))
                }
                if (sp.alreadySet.isNotEmpty()) addView(Ui.muted(ctx, "${sp.alreadySet.size} setting(s) already at the recommended value."))
            })
        }
        p.androidAnalysis?.let { a ->
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Recommendations", 16f, Ui.TEXT, true))
                addView(Ui.muted(ctx, "Tier ${a.tier.tier}; mode ${a.requested} → ${a.effective}. Nothing below was changed by this app."))
                a.recommendations.forEach { r ->
                    val tag = when (r.applicability) { Applicability.USER_ACTION -> "YOU DO THIS"; Applicability.INFORMATIONAL -> "INFO"; Applicability.NOT_AVAILABLE -> "NOT AVAILABLE" }
                    addView(Ui.text(ctx, "[$tag] ${r.title}", 13f, if (r.applicability == Applicability.NOT_AVAILABLE) Ui.WARN else Ui.TEXT, true))
                    addView(Ui.text(ctx, r.detail, 12f, Ui.MUTED))
                    if (r.action != SystemAction.NONE) addView(Ui.button(ctx, "Open settings", false) { openSystem(r.action, (p.target as GameTarget.Android).game.packageName) })
                }
            })
        }
    }

    private fun renderResult(root: LinearLayout, r: UniversalResult) = root.run {
        val color = when (r.outcome) {
            Outcome.APPLIED_AND_VERIFIED, Outcome.NO_CHANGE, Outcome.PROFILE_SAVED_RECOMMENDATIONS_ONLY -> Ui.OK
            Outcome.BLOCKED -> Ui.WARN; Outcome.FAILED -> Ui.BAD
        }
        addView(Ui.card(ctx).apply {
            addView(Ui.text(ctx, "Result: ${r.outcome}", 16f, color, true))
            addView(Ui.text(ctx, r.message, 13f))
            r.steps.filter { it.step == PipelineStep.SAVE || it.step == PipelineStep.MONITOR }
                .forEach { addView(Ui.kv(ctx, it.step.title, "${it.status}: ${it.detail}", Ui.statusColor(it.status.name))) }
            val t = r.plan.target
            if (t is GameTarget.Switch) addView(Ui.row(ctx,
                Ui.button(ctx, "Roll back last change", false) { maintenance { it.rollbackLast(t.id) } },
                Ui.button(ctx, "Reset to global", false) { maintenance { it.resetToGlobal(t.id) } }))
        })
    }

    private fun maintenance(op: (SwitchOptimizer) -> dev.nyushu.optimizer.core.switch.ApplyResult) {
        val app = App.get(ctx)
        val store = app.nyushuStore() ?: run { message = "No access to Nyushu folder"; refresh(); return }
        if (!closedConfirmed) { message = "Tick “Nyushu is completely closed” first."; refresh(); return }
        Async.run({ op(SwitchOptimizer(store, app.backups, app.compatDb())) }) { r ->
            message = r.fold({ it.toString() }, { "Failed: ${it.message}" }); plan = null; result = null; refresh()
        }
    }

    private fun openSystem(a: SystemAction, pkg: String) {
        val i = when (a) {
            SystemAction.OPEN_APP_INFO -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            SystemAction.OPEN_BATTERY_SAVER -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            SystemAction.OPEN_DISPLAY_SETTINGS -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            SystemAction.OPEN_USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            SystemAction.OPEN_STORAGE_SETTINGS -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            SystemAction.NONE -> return
        }
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { nav.toast("Cannot open that settings screen") }
    }
}
