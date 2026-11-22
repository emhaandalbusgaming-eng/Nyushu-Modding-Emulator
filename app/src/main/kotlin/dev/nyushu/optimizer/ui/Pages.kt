package dev.nyushu.optimizer.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** One screen. [build] creates the view tree; [onShow]/[onHide] let live pages start and stop timers. */
abstract class Page(protected val ctx: Context, protected val nav: Navigator) {
    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 24))
    }
    val root: View = ScrollView(ctx).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(content) }

    protected fun render(block: LinearLayout.() -> Unit) { content.removeAllViews(); content.block() }
    protected fun ui(block: () -> Unit) { root.post(block) }

    abstract fun refresh()
    open fun onShow() { refresh() }
    open fun onHide() {}
}

interface Navigator {
    fun go(id: PageId)
    fun openAuto(target: dev.nyushu.optimizer.core.universal.GameTarget)
    fun pickTree(requestCode: Int)
    fun pickFile(requestCode: Int)
    fun toast(msg: String)
}

enum class PageId(val label: String) {
    DASHBOARD("Dashboard"), SWITCH("Switch Games"), ANDROID("Android Games"), DEVICE("Device"),
    AUTO("Auto Optimizer"), PROFILES("Profiles"), MONITOR("Monitor"), SETTINGS("Settings")
}
