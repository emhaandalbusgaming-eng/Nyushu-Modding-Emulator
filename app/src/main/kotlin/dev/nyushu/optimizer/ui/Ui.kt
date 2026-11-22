package dev.nyushu.optimizer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    const val BG = 0xFF0F1115.toInt()
    const val CARD = 0xFF1A1D24.toInt()
    const val ACCENT = 0xFF4FC3F7.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val OK = 0xFF66BB6A.toInt()
    const val WARN = 0xFFFFB74D.toInt()
    const val BAD = 0xFFEF5350.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(c: Context, color: Int, radiusDp: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radiusDp).toFloat()
    }

    fun text(c: Context, s: CharSequence, size: Float = 14f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun title(c: Context, s: String) = text(c, s, 20f, TEXT, true).apply { setPadding(0, dp(c, 8), 0, dp(c, 8)) }
    fun muted(c: Context, s: String) = text(c, s, 12f, MUTED)

    fun card(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, CARD)
        setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(c, 6), 0, dp(c, 6)) }
    }

    fun button(c: Context, label: String, primary: Boolean = true, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = true; textSize = 12f
        setTextColor(if (primary) Color.BLACK else TEXT)
        background = rounded(c, if (primary) ACCENT else 0xFF2B303A.toInt(), 10)
        setPadding(dp(c, 14), 0, dp(c, 14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 40)).apply { setMargins(0, dp(c, 6), dp(c, 8), 0) }
        setOnClickListener { onClick() }
    }

    fun row(c: Context, vararg v: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    fun kv(c: Context, k: String, v: String, valueColor: Int = TEXT): View = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 3), 0, dp(c, 3))
        addView(text(c, k, 13f, MUTED).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f) })
        addView(text(c, v, 13f, valueColor).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f) })
    }

    /** Single-select chip row. */
    fun chips(c: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = ArrayList<Button>()
        fun paint(sel: Int) = btns.forEachIndexed { i, b ->
            b.background = rounded(c, if (i == sel) ACCENT else 0xFF2B303A.toInt(), 16)
            b.setTextColor(if (i == sel) Color.BLACK else TEXT)
        }
        labels.forEachIndexed { i, l ->
            val b = Button(c).apply {
                text = l; isAllCaps = false; textSize = 12f; minHeight = 0; minimumHeight = 0
                setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 36)).apply { setMargins(0, 0, dp(c, 6), 0) }
                setOnClickListener { paint(i); onSelect(i) }
            }
            btns += b; box.addView(b)
        }
        paint(selected)
        return android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(box) }
    }

    fun statusColor(s: String) = when (s) {
        "OK", "PASS" -> OK
        "WARN", "NOT_AVAILABLE", "PENDING", "SKIPPED" -> WARN
        "FAILED", "FAIL" -> BAD
        else -> TEXT
    }
}

/** Simple real-time line chart. Draws only real samples; gaps (NOT AVAILABLE) are simply absent. */
class LineChartView(c: Context) : View(c) {
    var title = ""
    var unit = ""
    var values: List<Double> = emptyList()
    var emptyMessage = "NOT AVAILABLE"
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; strokeWidth = Ui.dp(c, 2).toFloat(); style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.MUTED; textSize = Ui.dp(c, 11).toFloat() }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textSize = Ui.dp(c, 13).toFloat(); typeface = Typeface.DEFAULT_BOLD }
    private val grid = Paint().apply { color = 0xFF2B303A.toInt(); strokeWidth = 1f }

    init { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 110)) }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = Ui.dp(context, 22).toFloat(); val bottom = h - Ui.dp(context, 4)
        canvas.drawText(title, 0f, Ui.dp(context, 14).toFloat(), head)
        canvas.drawLine(0f, top, w, top, grid); canvas.drawLine(0f, bottom, w, bottom, grid)
        if (values.size < 2) {
            canvas.drawText(if (values.isEmpty()) emptyMessage else "collecting… %.1f%s".format(values[0], unit), 0f, (top + bottom) / 2, txt)
            return
        }
        var lo = values.min(); var hi = values.max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = w * i / (values.size - 1)
            val y = bottom - ((v - lo) / (hi - lo)).toFloat() * (bottom - top)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        canvas.drawPath(p, line)
        val last = values.last()
        val lbl = "%.1f%s".format(last, unit)
        canvas.drawText(lbl, w - txt.measureText(lbl), Ui.dp(context, 14).toFloat(), head)
        canvas.drawText("min %.1f  max %.1f".format(values.min(), values.max()), 0f, h - 0f, txt)
    }
}
