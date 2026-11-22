package dev.nyushu.optimizer.data

import android.content.Context
import dev.nyushu.optimizer.core.model.OptimizationMode

/** SharedPreferences wrapper. Nothing here is a secret; it only stores user choices and SAF grants. */
class AppSettings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var nyushuTreeUri: String? get() = sp.getString("nyushuTree", null); set(v) = sp.edit().putString("nyushuTree", v).apply()
    var gamesTreeUri: String? get() = sp.getString("gamesTree", null); set(v) = sp.edit().putString("gamesTree", v).apply()
    var nyushuPackage: String get() = sp.getString("nyushuPkg", DEFAULT_NYUSHU_PACKAGE)!!; set(v) = sp.edit().putString("nyushuPkg", v).apply()
    var nyushuAuthority: String get() = sp.getString("nyushuAuth", DEFAULT_NYUSHU_AUTHORITY)!!; set(v) = sp.edit().putString("nyushuAuth", v).apply()
    var showAllApps: Boolean get() = sp.getBoolean("allApps", false); set(v) = sp.edit().putBoolean("allApps", v).apply()
    var defaultMode: OptimizationMode
        get() = runCatching { OptimizationMode.valueOf(sp.getString("mode", "BALANCED")!!) }.getOrDefault(OptimizationMode.BALANCED)
        set(v) = sp.edit().putString("mode", v.name).apply()

    /** Switch games added by hand: "TITLEID|Name" */
    var manualSwitchGames: Set<String> get() = sp.getStringSet("manualSwitch", emptySet())!!; set(v) = sp.edit().putStringSet("manualSwitch", v).apply()
    var extraAndroidPackages: Set<String> get() = sp.getStringSet("extraPkgs", emptySet())!!; set(v) = sp.edit().putStringSet("extraPkgs", v).apply()

    companion object {
        const val DEFAULT_NYUSHU_PACKAGE = "com.andromeda.androbench2"
        const val DEFAULT_NYUSHU_AUTHORITY = "com.andromeda.androbench2.user"
    }
}
