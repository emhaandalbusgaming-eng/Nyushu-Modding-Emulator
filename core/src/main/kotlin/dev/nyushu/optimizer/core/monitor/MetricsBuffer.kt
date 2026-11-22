package dev.nyushu.optimizer.core.monitor

import dev.nyushu.optimizer.core.model.Reading

enum class Metric(val label: String, val unit: String) {
    RAM_USED_PCT("RAM used", "%"),
    BATTERY_TEMP_C("Battery temp", "°C"),
    THERMAL_STATUS("Thermal status", ""),
    BATTERY_PCT("Battery", "%"),
    BATTERY_POWER_MW("Battery power", "mW"),
    CPU_FREQ_MHZ("CPU freq (max core)", "MHz"),
    GPU_BUSY_PCT("GPU busy", "%"),
    CPU_LOAD_PCT("CPU load", "%"),
    FPS("FPS", ""),
    FRAME_TIME_MS("Frame time", "ms"),
}

data class Sample(val timestampMs: Long, val values: Map<Metric, Reading<Double>>)

class SeriesStats(val count: Int, val min: Double, val max: Double, val avg: Double, val last: Double)

/** Fixed-size ring buffer of real samples. A metric with no readable source is stored as NotAvailable, never as a made-up number. */
class MetricsBuffer(private val capacity: Int = 120) {
    private val samples = ArrayDeque<Sample>()
    val size get() = samples.size

    fun add(s: Sample) { samples.addLast(s); while (samples.size > capacity) samples.removeFirst() }
    fun all(): List<Sample> = samples.toList()

    fun series(m: Metric): List<Double> = samples.mapNotNull { it.values[m]?.orNull() }

    fun stats(m: Metric): SeriesStats? {
        val s = series(m)
        if (s.isEmpty()) return null
        return SeriesStats(s.size, s.min(), s.max(), s.average(), s.last())
    }

    /** Reason why a metric has no data, taken from the most recent sample (null if it has data). */
    fun unavailableReason(m: Metric): String? {
        val last = samples.lastOrNull()?.values?.get(m) ?: return "no sample yet"
        return (last as? Reading.NotAvailable)?.reason
    }
}
