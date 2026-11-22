package dev.nyushu.optimizer.data

import dev.nyushu.optimizer.core.model.Reading
import java.io.File

/** Best-effort readers for kernel files. Android SELinux often hides them from apps; failures become NOT AVAILABLE, never zero. */
object SystemReaders {
    private fun readOrNull(path: String): String? = try {
        File(path).takeIf { it.canRead() }?.readText()?.trim()
    } catch (e: Exception) { null }

    fun cpuMaxFreqMhz(): Reading<Int> {
        val vals = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
            readOrNull("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq")?.toLongOrNull()
        }
        return if (vals.isEmpty()) Reading.NotAvailable("cpufreq sysfs not readable by apps on this device")
        else Reading.Value((vals.max() / 1000).toInt())
    }

    fun cpuCurrentMaxFreqMhz(): Reading<Double> {
        val vals = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
            readOrNull("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.toLongOrNull()
        }
        return if (vals.isEmpty()) Reading.NotAvailable("scaling_cur_freq not readable by apps on this device")
        else Reading.Value(vals.max() / 1000.0)
    }

    fun gpuBusyPercent(): Reading<Double> {
        readOrNull("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { s ->
            s.filter { it.isDigit() || it == '.' }.toDoubleOrNull()?.let { return Reading.Value(it) }
        }
        readOrNull("/sys/class/kgsl/kgsl-3d0/gpubusy")?.split(Regex("\\s+"))?.let { p ->
            val busy = p.getOrNull(0)?.toDoubleOrNull(); val total = p.getOrNull(1)?.toDoubleOrNull()
            if (busy != null && total != null && total > 0) return Reading.Value(busy * 100.0 / total)
        }
        return Reading.NotAvailable("GPU busy sysfs not readable by apps on this device")
    }

    private var lastCpu: Pair<Long, Long>? = null

    /** /proc/stat is blocked for apps since Android 8; when it is readable we compute a real delta, otherwise NOT AVAILABLE. */
    fun cpuLoadPercent(): Reading<Double> {
        val line = readOrNull("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
            ?: return Reading.NotAvailable("/proc/stat not readable by apps (Android 8+)")
        val n = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 4) return Reading.NotAvailable("unexpected /proc/stat format")
        val idle = n[3] + n.getOrElse(4) { 0 }
        val total = n.sum()
        val prev = lastCpu
        lastCpu = total to idle
        if (prev == null) return Reading.NotAvailable("first sample, need a second one")
        val dt = total - prev.first
        if (dt <= 0) return Reading.NotAvailable("no time elapsed")
        return Reading.Value((dt - (idle - prev.second)) * 100.0 / dt)
    }
}
