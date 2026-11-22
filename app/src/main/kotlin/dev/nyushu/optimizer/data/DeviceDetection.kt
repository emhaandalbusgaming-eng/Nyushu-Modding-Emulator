package dev.nyushu.optimizer.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.view.Display
import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.model.ThermalLevel

/** Builds a [DeviceSnapshot] from what Android actually reports. Nothing is hardcoded to a device model. */
class DeviceDetection(private val ctx: Context, private val settings: AppSettings) {
    @Volatile private var glCache: GlInfo? = null

    private fun gl(): GlInfo = glCache ?: GpuProbe.query().also { glCache = it }

    fun snapshot(): DeviceSnapshot {
        val g = gl()
        val ram = ramInfo()
        val stat = StatFs(Environment.getDataDirectory().path)
        val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val bat = batteryIntent()
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER, model = Build.MODEL,
            socName = soc(), abis = Build.SUPPORTED_ABIS.toList(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(), cpuMaxFreqMhz = SystemReaders.cpuMaxFreqMhz(),
            gpuRenderer = g.renderer, gpuVendor = g.vendor, glEsVersion = g.version,
            vulkanApiVersion = vulkan(),
            ramTotalMb = ram.totalMem / MB, ramAvailMb = ram.availMem / MB,
            storageTotalMb = stat.totalBytes / MB, storageFreeMb = stat.availableBytes / MB,
            displayRefreshHz = display?.refreshRate ?: 60f,
            supportedRefreshHz = display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted() ?: emptyList(),
            androidRelease = Build.VERSION.RELEASE, sdkInt = Build.VERSION.SDK_INT,
            kernel = System.getProperty("os.version") ?: "unknown",
            thermal = thermal(), batteryTempC = batteryTemp(bat), batteryPercent = batteryPct(bat), charging = charging(bat),
            emulatorVersion = emulatorVersion(),
        )
    }

    fun ramInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }

    fun batteryIntent(): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun thermal(): Reading<ThermalLevel> = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        Reading.Value(ThermalLevel.fromAndroid(pm.currentThermalStatus))
    } catch (e: Throwable) { Reading.NotAvailable("PowerManager thermal status failed: ${e.javaClass.simpleName}") }

    fun batteryTemp(i: Intent? = batteryIntent()): Reading<Float> {
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (t == Int.MIN_VALUE) Reading.NotAvailable("battery temperature not reported") else Reading.Value(t / 10f)
    }

    fun batteryPct(i: Intent? = batteryIntent()): Reading<Int> {
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) Reading.NotAvailable("battery level not reported") else Reading.Value(level * 100 / scale)
    }

    fun charging(i: Intent? = batteryIntent()): Reading<Boolean> {
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return if (st < 0) Reading.NotAvailable("charging state not reported")
        else Reading.Value(st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    /** Instantaneous battery power in mW from CURRENT_NOW (µA) × voltage; sign varies by vendor so the magnitude is reported. */
    fun batteryPowerMw(i: Intent? = batteryIntent()): Reading<Double> {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (ua == Long.MIN_VALUE || ua == 0L || mv <= 0) return Reading.NotAvailable("current/voltage not reported")
        return Reading.Value(Math.abs(ua) / 1000.0 * mv / 1000.0)
    }

    private fun soc(): Reading<String> {
        if (Build.VERSION.SDK_INT >= 31) {
            val m = Build.SOC_MODEL
            if (m.isNotBlank() && m != Build.UNKNOWN) return Reading.Value("${Build.SOC_MANUFACTURER} $m".trim())
        }
        val hw = Build.HARDWARE
        return if (hw.isNotBlank()) Reading.Value("$hw (Build.HARDWARE; exact SoC model needs Android 12+)")
        else Reading.NotAvailable("SoC not reported")
    }

    private fun vulkan(): Reading<String> {
        val pm = ctx.packageManager
        val levels = listOf(0x403000 to "1.3", 0x402000 to "1.2", 0x401000 to "1.1", 0x400003 to "1.0")
        for ((v, name) in levels) if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, v)) return Reading.Value("$name (system feature)")
        return Reading.NotAvailable("device does not declare a Vulkan hardware version")
    }

    fun emulatorVersion(): Reading<String> = try {
        val pi = ctx.packageManager.getPackageInfo(settings.nyushuPackage, 0)
        Reading.Value("${pi.versionName ?: "?"} (${settings.nyushuPackage})")
    } catch (e: PackageManager.NameNotFoundException) {
        Reading.NotAvailable("${settings.nyushuPackage} not installed or not visible")
    }

    companion object { private const val MB = 1024L * 1024L }
}
