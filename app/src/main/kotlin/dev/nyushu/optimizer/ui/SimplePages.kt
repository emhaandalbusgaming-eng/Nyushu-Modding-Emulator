package dev.nyushu.optimizer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.nyushu.optimizer.App
import dev.nyushu.optimizer.core.device.DeviceClassifier
import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.OptimizationMode
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.monitor.Metric
import dev.nyushu.optimizer.core.profile.GameKind
import dev.nyushu.optimizer.core.switch.TitleId
import dev.nyushu.optimizer.monitor.MonitorService
import java.text.DateFormat
import java.util.Date

object DeviceReport {
    fun lines(d: DeviceSnapshot): List<Pair<String, String>> {
        val t = DeviceClassifier.classify(d)
        fun r(x: Reading<*>, unit: String = "") = when (x) {
            is Reading.Value<*> -> "${x.value}$unit"
            is Reading.NotAvailable -> "NOT AVAILABLE (${x.reason})"
        }
        return listOf(
            "Device" to "${d.manufacturer} ${d.model}",
            "SoC" to r(d.socName),
            "ABI" to d.abis.joinToString(),
            "CPU cores" to d.cpuCoreCount.toString(),
            "CPU max frequency" to r(d.cpuMaxFreqMhz, " MHz"),
            "GPU renderer" to r(d.gpuRenderer),
            "GPU vendor" to r(d.gpuVendor),
            "OpenGL ES" to r(d.glEsVersion),
            "Vulkan" to r(d.vulkanApiVersion),
            "RAM" to "${d.ramAvailMb} MB free of ${d.ramTotalMb} MB",
            "Storage" to "${d.storageFreeMb} MB free of ${d.storageTotalMb} MB",
            "Display" to "%.0f Hz (supported: %s)".format(d.displayRefreshHz, d.supportedRefreshHz.joinToString { "%.0f".format(it) }),
            "Android" to "${d.androidRelease} (API ${d.sdkInt})",
            "Kernel" to d.kernel,
            "Thermal status" to r(d.thermal),
            "Battery temperature" to r(d.batteryTempC, " °C"),
            "Battery" to "${r(d.batteryPercent, "%")}, charging: ${r(d.charging)}",
            "Emulator (Nyushu)" to r(d.emulatorVersion),
            "Estimated tier" to "${t.tier} — ${t.basis}",
        )
    }
}

class DashboardPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    override fun refresh() {
        val app = App.get(ctx)
        Async.run({ app.detection.snapshot() to (app.nyushuStore() != null) }) { res ->
            val (d, access) = res.getOrNull() ?: return@run
            val t = DeviceClassifier.classify(d)
            render {
                addView(Ui.title(ctx, "Nyushu Universal Gaming Optimizer"))
                addView(Ui.card(ctx).apply {
                    addView(Ui.text(ctx, "${d.manufacturer} ${d.model}", 16f, Ui.TEXT, true))
                    addView(Ui.kv(ctx, "GPU", d.gpuRenderer.display()))
                    addView(Ui.kv(ctx, "Tier", "${t.tier}"))
                    addView(Ui.kv(ctx, "RAM free", "${d.ramAvailMb} / ${d.ramTotalMb} MB"))
                    addView(Ui.kv(ctx, "Thermal", d.thermal.display()))
                    addView(Ui.kv(ctx, "Battery", "${d.batteryPercent.display("%")} · ${d.batteryTempC.display("°C")}"))
                })
                addView(Ui.card(ctx).apply {
                    addView(Ui.text(ctx, "Nyushu emulator", 16f, Ui.TEXT, true))
                    addView(Ui.kv(ctx, "Installed", d.emulatorVersion.display()))
                    addView(Ui.kv(ctx, "Config access", if (access) "GRANTED" else "NOT GRANTED", if (access) Ui.OK else Ui.WARN))
                    if (!access) addView(Ui.button(ctx, "Grant access", true) { nav.go(PageId.SETTINGS) })
                })
                addView(Ui.card(ctx).apply {
                    addView(Ui.text(ctx, "Profiles saved: ${app.profiles.all().size}", 14f))
                    addView(Ui.row(ctx,
                        Ui.button(ctx, "Switch games", false) { nav.go(PageId.SWITCH) },
                        Ui.button(ctx, "Android games", false) { nav.go(PageId.ANDROID) }))
                })
            }
        }
    }
}

class DevicePage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    override fun refresh() {
        Async.run({ App.get(ctx).detection.snapshot() }) { res ->
            val d = res.getOrNull() ?: return@run
            val lines = DeviceReport.lines(d)
            render {
                addView(Ui.title(ctx, "Device Intelligence"))
                addView(Ui.muted(ctx, "Read live from Android. Anything the system hides from apps is shown as NOT AVAILABLE with the reason."))
                addView(Ui.card(ctx).apply { lines.forEach { (k, v) -> addView(Ui.kv(ctx, k, v, if (v.startsWith("NOT AVAILABLE")) Ui.WARN else Ui.TEXT)) } })
                addView(Ui.row(ctx,
                    Ui.button(ctx, "Refresh", false) { refresh() },
                    Ui.button(ctx, "Copy report", true) {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("device", lines.joinToString("\n") { "${it.first}: ${it.second}" }))
                        nav.toast("Copied")
                    }))
            }
        }
    }
}

class ProfilesPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    override fun refresh() {
        val app = App.get(ctx)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        render {
            addView(Ui.title(ctx, "Per-Game Profiles"))
            val all = app.profiles.all()
            if (all.isEmpty()) addView(Ui.muted(ctx, "No profiles yet. Run AUTO OPTIMIZE on a game."))
            for (p in all) addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, p.name, 16f, Ui.TEXT, true))
                addView(Ui.kv(ctx, if (p.kind == GameKind.SWITCH) "Title ID" else "Package", p.id))
                addView(Ui.kv(ctx, "Type", if (p.kind == GameKind.SWITCH) "Nintendo Switch (Nyushu)" else "Android game"))
                addView(Ui.kv(ctx, "Current profile", p.mode.name))
                addView(Ui.kv(ctx, "Recommended", p.recommendedMode?.name ?: "—"))
                if (p.customSettings.isNotEmpty()) addView(Ui.kv(ctx, "Custom", p.customSettings.entries.joinToString { "${it.key}=${it.value}" }))
                addView(Ui.text(ctx, "History", 12f, Ui.MUTED))
                p.history.takeLast(5).reversed().forEach {
                    addView(Ui.text(ctx, "${if (it.success) "✔" else "✖"} ${df.format(Date(it.timestampMs))} ${it.action}: ${it.summary}", 12f,
                        if (it.success) Ui.TEXT else Ui.BAD))
                }
                addView(Ui.row(ctx,
                    Ui.button(ctx, "Delete", false) { app.profiles.delete(p.kind, p.id); refresh() }))
            })
        }
    }
}

class MonitorPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    private val charts = LinkedHashMap<Metric, LineChartView>()
    private val order = listOf(Metric.RAM_USED_PCT, Metric.BATTERY_TEMP_C, Metric.THERMAL_STATUS, Metric.BATTERY_POWER_MW,
        Metric.CPU_FREQ_MHZ, Metric.GPU_BUSY_PCT, Metric.CPU_LOAD_PCT, Metric.FPS, Metric.FRAME_TIME_MS)
    private val tick = object : Runnable {
        override fun run() { update(); root.postDelayed(this, 2000) }
    }

    override fun refresh() {
        val hub = App.get(ctx).monitor
        charts.clear()
        render {
            addView(Ui.title(ctx, "Performance Monitor"))
            addView(Ui.muted(ctx, "Real samples every 2 s. A chart that cannot be fed by a readable source says NOT AVAILABLE; nothing is simulated."))
            addView(Ui.row(ctx,
                Ui.button(ctx, "Keep running in background", false) {
                    val i = Intent(ctx, MonitorService::class.java)
                    ctx.startForegroundService(i); nav.toast("Monitoring service started")
                },
                Ui.button(ctx, "Stop", false) { ctx.stopService(Intent(ctx, MonitorService::class.java)); hub.stop(); nav.toast("Stopped") }))
            for (m in order) {
                val c = LineChartView(ctx).apply { title = m.label; unit = m.unit }
                charts[m] = c
                addView(Ui.card(ctx).apply { addView(c) })
            }
            addView(Ui.text(ctx, "", 12f, Ui.MUTED).apply { tag = "profile" })
        }
        hub.start(); update()
    }

    private fun update() {
        val hub = App.get(ctx).monitor
        for ((m, c) in charts) {
            c.values = hub.buffer.series(m)
            c.emptyMessage = "NOT AVAILABLE — ${hub.buffer.unavailableReason(m) ?: "no data"}"
            c.invalidate()
        }
    }

    override fun onShow() { super.onShow(); root.postDelayed(tick, 2000) }
    override fun onHide() { root.removeCallbacks(tick) }
}

class SettingsPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    override fun refresh() {
        val app = App.get(ctx); val s = app.settings
        render {
            addView(Ui.title(ctx, "Settings"))
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Nyushu data folder", 16f, Ui.TEXT, true))
                addView(Ui.muted(ctx, "Needed to read and write per-game configs. Pick Nyushu's root in the system file picker (it appears as a storage provider). This app only touches config/custom and reads config/config.ini."))
                addView(Ui.kv(ctx, "Status", if (app.nyushuStore() != null) "GRANTED" else "NOT GRANTED", if (app.nyushuStore() != null) Ui.OK else Ui.WARN))
                addView(Ui.row(ctx, Ui.button(ctx, "Choose folder", true) { nav.pickTree(1) }))
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Switch games folder (optional)", 16f, Ui.TEXT, true))
                addView(Ui.muted(ctx, "File names containing [TitleID] are used to list games. Files are never opened or parsed."))
                addView(Ui.kv(ctx, "Status", if (s.gamesTreeUri != null) "SELECTED" else "NOT SELECTED"))
                addView(Ui.row(ctx, Ui.button(ctx, "Choose folder", false) { nav.pickTree(2) },
                    Ui.button(ctx, "Clear", false) { s.gamesTreeUri = null; refresh() }))
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Compatibility database (optional)", 16f, Ui.TEXT, true))
                addView(Ui.muted(ctx, "CSV with lines: TitleID,status,note. Without it every game is UNKNOWN; compatibility is never guessed."))
                addView(Ui.row(ctx, Ui.button(ctx, "Import CSV", false) { nav.pickFile(3) }))
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Emulator identity", 16f, Ui.TEXT, true))
                val pkg = EditText(ctx).apply { setText(s.nyushuPackage); setTextColor(Ui.TEXT); inputType = InputType.TYPE_CLASS_TEXT }
                val auth = EditText(ctx).apply { setText(s.nyushuAuthority); setTextColor(Ui.TEXT); inputType = InputType.TYPE_CLASS_TEXT }
                addView(Ui.muted(ctx, "Package")); addView(pkg)
                addView(Ui.muted(ctx, "Documents authority")); addView(auth)
                addView(Ui.row(ctx, Ui.button(ctx, "Save", true) {
                    s.nyushuPackage = pkg.text.toString().trim(); s.nyushuAuthority = auth.text.toString().trim(); nav.toast("Saved")
                }))
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Default mode", 16f, Ui.TEXT, true))
                addView(Ui.chips(ctx, OptimizationMode.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                    s.defaultMode.ordinal) { s.defaultMode = OptimizationMode.entries[it] })
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Active-game detection", 16f, Ui.TEXT, true))
                addView(Ui.kv(ctx, "Usage Access", if (app.foreground.hasUsageAccess()) "GRANTED" else "NOT GRANTED"))
                addView(Ui.row(ctx, Ui.button(ctx, "Open Usage Access", false) {
                    ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }))
            })
            addView(Ui.card(ctx).apply {
                addView(Ui.text(ctx, "Add Switch game by hand", 16f, Ui.TEXT, true))
                val id = EditText(ctx).apply { hint = "Title ID (16 hex digits)"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED) }
                val name = EditText(ctx).apply { hint = "Name (optional)"; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED) }
                addView(id); addView(name)
                addView(Ui.row(ctx, Ui.button(ctx, "Add", true) {
                    val n = TitleId.normalize(id.text.toString())
                    if (n == null) nav.toast("Title ID must be 16 hex digits")
                    else { s.manualSwitchGames = s.manualSwitchGames.filterNot { it.startsWith(n) }.toSet() + "$n|${name.text.toString().replace('|', ' ').trim()}"; nav.toast("Added") }
                }))
            })
        }
    }
}
