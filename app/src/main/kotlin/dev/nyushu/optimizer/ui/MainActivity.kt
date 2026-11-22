package dev.nyushu.optimizer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import dev.nyushu.optimizer.App
import dev.nyushu.optimizer.core.universal.GameTarget

class MainActivity : Activity(), Navigator {
    private lateinit var frame: FrameLayout
    private lateinit var pages: Map<PageId, Page>
    private val tabs = HashMap<PageId, Button>()
    private var current: PageId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG; window.navigationBarColor = Ui.BG
        pages = mapOf(
            PageId.DASHBOARD to DashboardPage(this, this), PageId.SWITCH to SwitchLibraryPage(this, this),
            PageId.ANDROID to AndroidLibraryPage(this, this), PageId.DEVICE to DevicePage(this, this),
            PageId.AUTO to AutoOptimizerPage(this, this), PageId.PROFILES to ProfilesPage(this, this),
            PageId.MONITOR to MonitorPage(this, this), PageId.SETTINGS to SettingsPage(this, this),
        )
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 6)) }
        for (id in PageId.entries) {
            val b = Button(this).apply {
                text = id.label; isAllCaps = false; textSize = 12f; minHeight = 0; minimumHeight = 0
                setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this@MainActivity, 36)).apply { setMargins(0, 0, Ui.dp(this@MainActivity, 6), 0) }
                setOnClickListener { go(id) }
            }
            tabs[id] = b; strip.addView(b)
        }
        frame = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
            addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        go(PageId.DASHBOARD)
    }

    override fun go(id: PageId) {
        current?.let { pages[it]?.onHide() }
        current = id
        frame.removeAllViews(); frame.addView(pages.getValue(id).root)
        for ((k, b) in tabs) {
            b.background = Ui.rounded(this, if (k == id) Ui.ACCENT else 0xFF2B303A.toInt(), 16)
            b.setTextColor(if (k == id) android.graphics.Color.BLACK else Ui.TEXT)
        }
        pages.getValue(id).onShow()
    }

    override fun openAuto(target: GameTarget) {
        (pages.getValue(PageId.AUTO) as AutoOptimizerPage).open(target); go(PageId.AUTO)
    }

    override fun onResume() { super.onResume(); current?.let { pages[it]?.onShow() } }
    override fun onPause() { super.onPause(); current?.let { pages[it]?.onHide() } }
    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun pickTree(requestCode: Int) {
        val s = App.get(this).settings
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (requestCode == 1) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildTreeDocumentUri(s.nyushuAuthority, "root"))
        startActivityForResult(i, requestCode)
    }

    override fun pickFile(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), requestCode)
    }

    @Deprecated("platform API; avoids an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val s = App.get(this).settings
        when (requestCode) {
            1 -> {
                if (uri.authority != s.nyushuAuthority) {
                    toast("That folder is not Nyushu's (expected ${s.nyushuAuthority}, got ${uri.authority}). Pick Nyushu in the file picker side menu.")
                } else {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    s.nyushuTreeUri = uri.toString(); toast("Nyushu folder access granted")
                }
            }
            2 -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                s.gamesTreeUri = uri.toString(); toast("Games folder selected")
            }
            3 -> Async.run({
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                java.io.File(filesDir, "compat.csv").writeText(text); App.get(this).compatDb().size
            }) { r -> toast(r.fold({ "Imported $it entries" }, { "Import failed: ${it.message}" })) }
        }
        current?.let { pages[it]?.refresh() }
    }
}
