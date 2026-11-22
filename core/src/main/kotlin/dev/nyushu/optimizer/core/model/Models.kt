package dev.nyushu.optimizer.core.model

enum class OptimizationMode { BALANCED, PERFORMANCE, COOL, CUSTOM }

enum class ThermalLevel(val androidStatus: Int) {
    NONE(0), LIGHT(1), MODERATE(2), SEVERE(3), CRITICAL(4), EMERGENCY(5), SHUTDOWN(6);

    companion object {
        fun fromAndroid(status: Int): ThermalLevel = entries.firstOrNull { it.androidStatus == status } ?: NONE
    }
}

/** A value that either was really read from the device, or is explicitly NOT AVAILABLE (with the reason). */
sealed class Reading<out T> {
    data class Value<T>(val value: T) : Reading<T>()
    data class NotAvailable(val reason: String) : Reading<Nothing>()

    fun orNull(): T? = (this as? Value<T>)?.value
    fun display(unit: String = ""): String = when (this) {
        is Value -> "$value$unit"
        is NotAvailable -> "NOT AVAILABLE"
    }
}

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val socName: Reading<String>,
    val abis: List<String>,
    val cpuCoreCount: Int,
    val cpuMaxFreqMhz: Reading<Int>,
    val gpuRenderer: Reading<String>,
    val gpuVendor: Reading<String>,
    val glEsVersion: Reading<String>,
    val vulkanApiVersion: Reading<String>,
    val ramTotalMb: Long,
    val ramAvailMb: Long,
    val storageTotalMb: Long,
    val storageFreeMb: Long,
    val displayRefreshHz: Float,
    val supportedRefreshHz: List<Float>,
    val androidRelease: String,
    val sdkInt: Int,
    val kernel: String,
    val thermal: Reading<ThermalLevel>,
    val batteryTempC: Reading<Float>,
    val batteryPercent: Reading<Int>,
    val charging: Reading<Boolean>,
    val emulatorVersion: Reading<String>,
)

enum class DeviceTier(val rank: Int) { LOW(0), MID(1), HIGH(2), FLAGSHIP(3) }
