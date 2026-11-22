package dev.nyushu.optimizer.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.nyushu.optimizer.App
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.monitor.Metric
import dev.nyushu.optimizer.core.monitor.MetricsBuffer
import dev.nyushu.optimizer.core.monitor.Sample
import dev.nyushu.optimizer.data.DeviceDetection
import dev.nyushu.optimizer.data.SystemReaders
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Collects real samples every 2 s into a ring buffer. Metrics without a readable source stay NOT AVAILABLE. */
class MonitorHub(private val det: DeviceDetection) {
    val buffer = MetricsBuffer(150)
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null
    @Volatile var listener: (() -> Unit)? = null
    val running get() = task != null

    @Synchronized fun start() {
        if (task != null) return
        task = exec.scheduleWithFixedDelay({ runCatching { buffer.add(sampleNow()); listener?.invoke() } }, 0, 2, TimeUnit.SECONDS)
    }

    @Synchronized fun stop() { task?.cancel(false); task = null }

    fun sampleNow(): Sample {
        val ram = det.ramInfo()
        val bat = det.batteryIntent()
        val used = if (ram.totalMem > 0) (ram.totalMem - ram.availMem) * 100.0 / ram.totalMem else 0.0
        val th = det.thermal()
        return Sample(System.currentTimeMillis(), mapOf(
            Metric.RAM_USED_PCT to Reading.Value(used),
            Metric.BATTERY_TEMP_C to det.batteryTemp(bat).let { if (it is Reading.Value) Reading.Value(it.value.toDouble()) else it as Reading.NotAvailable },
            Metric.THERMAL_STATUS to when (th) { is Reading.Value -> Reading.Value(th.value.androidStatus.toDouble()); is Reading.NotAvailable -> th },
            Metric.BATTERY_PCT to det.batteryPct(bat).let { if (it is Reading.Value) Reading.Value(it.value.toDouble()) else it as Reading.NotAvailable },
            Metric.BATTERY_POWER_MW to det.batteryPowerMw(bat),
            Metric.CPU_FREQ_MHZ to SystemReaders.cpuCurrentMaxFreqMhz(),
            Metric.GPU_BUSY_PCT to SystemReaders.gpuBusyPercent(),
            Metric.CPU_LOAD_PCT to SystemReaders.cpuLoadPercent(),
            Metric.FPS to Reading.NotAvailable("Android exposes no FPS source for other apps without privileged access"),
            Metric.FRAME_TIME_MS to Reading.NotAvailable("Android exposes no frame-time source for other apps without privileged access"),
        ))
    }
}

/** Optional foreground service so sampling continues while a game is in front. Shows the latest real values in its notification. */
class MonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Performance monitor", NotificationManager.IMPORTANCE_LOW))
        val hub = App.get(this).monitor
        fun notif(text: String) = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("Nyushu Optimizer monitoring")
            .setContentText(text).setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startForeground(1, notif("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notif("Starting…"))
        hub.listener = {
            val s = hub.buffer.all().lastOrNull()
            if (s != null) {
                fun v(m: Metric, unit: String) = (s.values[m] as? Reading.Value)?.let { "%.0f%s".format(it.value, unit) } ?: "n/a"
                nm.notify(1, notif("RAM ${v(Metric.RAM_USED_PCT, "%")}  Batt ${v(Metric.BATTERY_TEMP_C, "°C")}  Thermal ${v(Metric.THERMAL_STATUS, "")}"))
            }
        }
        hub.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        App.get(this).monitor.listener = null
        super.onDestroy()
    }

    companion object { const val CHANNEL = "monitor" }
}
