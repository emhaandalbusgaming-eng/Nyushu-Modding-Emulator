package dev.nyushu.optimizer.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import dev.nyushu.optimizer.core.android.InstalledGame

/** Lists launcher-visible apps that Android categorises as games (plus user-added ones). Uses <queries>, not QUERY_ALL_PACKAGES. */
class GameScanner(private val ctx: Context, private val settings: AppSettings) {
    private val pm: PackageManager get() = ctx.packageManager

    @Suppress("DEPRECATION")
    fun scan(): List<InstalledGame> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val extra = settings.extraAndroidPackages
        val all = settings.showAllApps
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName && it.packageName != settings.nyushuPackage }
            .filter { all || isGame(it) || it.packageName in extra }
            .mapNotNull { toGame(it) }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo): Boolean =
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    @Suppress("DEPRECATION")
    private fun toGame(ai: ApplicationInfo): InstalledGame? = try {
        val pi = pm.getPackageInfo(ai.packageName, 0)
        val full = pm.getApplicationInfo(ai.packageName, PackageManager.GET_META_DATA)
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ai.packageName).installingPackageName else null
        InstalledGame(
            packageName = ai.packageName, label = pm.getApplicationLabel(ai).toString(),
            versionName = pi.versionName, versionCode = pi.longVersionCode,
            targetSdk = ai.targetSdkVersion, minSdk = ai.minSdkVersion,
            declaresGameModeConfig = full.metaData?.containsKey("android.game_mode_config") == true,
            isGameCategory = isGame(ai), installerPackage = installer,
        )
    } catch (e: Exception) { null }

    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)
}

/** Foreground-app detection needs the user to grant Usage Access. When not granted the result is null (NOT AVAILABLE). */
class ForegroundDetector(private val ctx: Context) {
    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentForegroundPackage(): String? {
        if (!hasUsageAccess()) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 60_000, end)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
            else if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && e.packageName == last) last = null
        }
        return last
    }
}
