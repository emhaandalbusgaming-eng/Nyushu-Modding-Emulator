package dev.nyushu.optimizer.ui

import android.content.Context
import android.widget.CheckBox
import dev.nyushu.optimizer.App
import dev.nyushu.optimizer.core.profile.GameKind
import dev.nyushu.optimizer.core.switch.GameRef
import dev.nyushu.optimizer.core.switch.TitleKind
import dev.nyushu.optimizer.core.universal.GameTarget
import java.text.DateFormat
import java.util.Date

private fun lastHistory(ctx: Context, kind: GameKind, id: String): String {
    val h = App.get(ctx).profiles.get(kind, id)?.history?.lastOrNull() ?: return "none"
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return "${if (h.success) "✔" else "✖"} ${df.format(Date(h.timestampMs))} ${h.summary}"
}

class SwitchLibraryPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    override fun refresh() {
        val app = App.get(ctx)
        Async.run({
            val store = app.nyushuStore()
            val lib = app.switchLibrary.load(store)
            val rec = app.universalForAndroid().recommendMode(app.detection.snapshot())
            Triple(lib, rec, app.compatDb())
        }) { res ->
            val (lib, rec, compat) = res.getOrElse { e ->
                render { addView(Ui.title(ctx, "Switch Games")); addView(Ui.text(ctx, "Error: ${e.message}", 14f, Ui.BAD)) }; return@run
            }
            render {
                addView(Ui.title(ctx, "Switch Game Library"))
                lib.notes.forEach { addView(Ui.muted(ctx, "• $it")) }
                if (lib.entries.isEmpty()) addView(Ui.text(ctx, "No games found yet.", 14f, Ui.MUTED))
                for (e in lib.entries) {
                    val prof = app.profiles.get(GameKind.SWITCH, e.titleId)
                    val c = compat.lookup(e.titleId)
                    addView(Ui.card(ctx).apply {
                        addView(Ui.text(ctx, e.name ?: "Unnamed game", 16f, Ui.TEXT, true))
                        addView(Ui.kv(ctx, "Title ID", e.titleId))
                        addView(Ui.kv(ctx, "Type", "Nintendo Switch · " + when (e.kind) {
                            TitleKind.BASE_GAME -> "base game"; TitleKind.UPDATE -> "update"; TitleKind.DLC -> "DLC"; TitleKind.OTHER -> "other/homebrew" }))
                        addView(Ui.kv(ctx, "Found in", e.sources.joinToString()))
                        addView(Ui.kv(ctx, "Compatibility", c.status.name + if (c.note.isNotBlank()) " — ${c.note}" else ""))
                        addView(Ui.kv(ctx, "Current profile", prof?.mode?.name ?: "none"))
                        addView(Ui.kv(ctx, "Recommended", rec.name))
                        addView(Ui.kv(ctx, "Last optimization", lastHistory(ctx, GameKind.SWITCH, e.titleId)))
                        addView(Ui.row(ctx,
                            Ui.button(ctx, "Auto optimize", true) { nav.openAuto(GameTarget.Switch(GameRef(e.titleId, e.name, e.sources.first()))) },
                            Ui.button(ctx, "Open Nyushu", false) { launchNyushu() }))
                    })
                }
            }
        }
    }

    private fun launchNyushu() {
        val s = App.get(ctx).settings
        val i = ctx.packageManager.getLaunchIntentForPackage(s.nyushuPackage)
        if (i == null) nav.toast("Nyushu (${s.nyushuPackage}) is not installed or not visible")
        else { nav.toast("Nyushu cannot be told which game to start from outside; start the game inside Nyushu."); ctx.startActivity(i) }
    }
}

class AndroidLibraryPage(ctx: Context, nav: Navigator) : Page(ctx, nav) {
    override fun refresh() {
        val app = App.get(ctx)
        Async.run({
            Triple(app.scanner.scan(), app.universalForAndroid().recommendMode(app.detection.snapshot()), app.foreground.currentForegroundPackage())
        }) { res ->
            val (games, rec, fg) = res.getOrElse { e ->
                render { addView(Ui.title(ctx, "Android Games")); addView(Ui.text(ctx, "Error: ${e.message}", 14f, Ui.BAD)) }; return@run
            }
            render {
                addView(Ui.title(ctx, "Android Game Library"))
                addView(Ui.muted(ctx, "Engine B can analyze and recommend. It cannot change another game's graphics settings, and never claims to."))
                addView(CheckBox(ctx).apply {
                    text = "Show every launcher app (for games Android does not categorise)"; setTextColor(Ui.TEXT)
                    isChecked = app.settings.showAllApps
                    setOnCheckedChangeListener { _, v -> app.settings.showAllApps = v; refresh() }
                })
                addView(Ui.muted(ctx, if (app.foreground.hasUsageAccess()) "Active app: ${fg ?: "unknown"}" else "Active-game detection: NOT AVAILABLE (Usage Access not granted, see Settings)"))
                if (games.isEmpty()) addView(Ui.text(ctx, "No games found.", 14f, Ui.MUTED))
                for (g in games) {
                    val prof = app.profiles.get(GameKind.ANDROID, g.packageName)
                    addView(Ui.card(ctx).apply {
                        addView(Ui.text(ctx, g.label + if (g.packageName == fg) "  ● active" else "", 16f, Ui.TEXT, true))
                        addView(Ui.kv(ctx, "Package", g.packageName))
                        addView(Ui.kv(ctx, "Version", "${g.versionName ?: "?"} (${g.versionCode})"))
                        addView(Ui.kv(ctx, "Type", "Native Android game" + if (g.isGameCategory) "" else " (not categorised as game)"))
                        addView(Ui.kv(ctx, "Game Mode config", if (g.declaresGameModeConfig) "declared" else "not declared"))
                        addView(Ui.kv(ctx, "Current profile", prof?.mode?.name ?: "none"))
                        addView(Ui.kv(ctx, "Recommended", rec.name))
                        addView(Ui.kv(ctx, "Last optimization", lastHistory(ctx, GameKind.ANDROID, g.packageName)))
                        addView(Ui.row(ctx,
                            Ui.button(ctx, "Auto optimize", true) { nav.openAuto(GameTarget.Android(g)) },
                            Ui.button(ctx, "Launch", false) {
                                app.scanner.launchIntent(g.packageName)?.let { ctx.startActivity(it) } ?: nav.toast("Cannot launch")
                            }))
                    })
                }
            }
        }
    }
}
