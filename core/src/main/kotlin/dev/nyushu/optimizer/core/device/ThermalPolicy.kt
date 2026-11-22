package dev.nyushu.optimizer.core.device

import dev.nyushu.optimizer.core.model.OptimizationMode
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.model.ThermalLevel

data class ThermalAdvice(
    val requested: OptimizationMode,
    val effective: OptimizationMode,
    val messages: List<String>,
) { val downgraded get() = requested != effective }

/** Reduces (never raises) the requested mode when the device reports heat. Thermal protection itself is never touched. */
object ThermalPolicy {
    const val BATTERY_WARM_C = 42f
    const val BATTERY_HOT_C = 45f

    fun evaluate(requested: OptimizationMode, thermal: Reading<ThermalLevel>, batteryTempC: Reading<Float>): ThermalAdvice {
        val msgs = mutableListOf<String>()
        var effective = requested
        val level = thermal.orNull()
        val temp = batteryTempC.orNull()

        if (thermal is Reading.NotAvailable) msgs += "Thermal status NOT AVAILABLE: ${thermal.reason}"
        if (batteryTempC is Reading.NotAvailable) msgs += "Battery temperature NOT AVAILABLE: ${batteryTempC.reason}"

        fun capAt(cap: OptimizationMode, why: String) {
            if (requested == OptimizationMode.CUSTOM) { msgs += "$why (custom profile left unchanged)"; return }
            val order = listOf(OptimizationMode.PERFORMANCE, OptimizationMode.BALANCED, OptimizationMode.COOL)
            if (order.indexOf(effective) < order.indexOf(cap)) { effective = cap; msgs += "$why: mode lowered to $cap" }
        }

        if (level != null) when {
            level.androidStatus >= ThermalLevel.SEVERE.androidStatus -> capAt(OptimizationMode.COOL, "Thermal status $level")
            level == ThermalLevel.MODERATE -> capAt(OptimizationMode.BALANCED, "Thermal status $level")
            else -> {}
        }
        if (temp != null) when {
            temp >= BATTERY_HOT_C -> capAt(OptimizationMode.COOL, "Battery temperature ${temp}°C")
            temp >= BATTERY_WARM_C -> capAt(OptimizationMode.BALANCED, "Battery temperature ${temp}°C")
            else -> {}
        }
        if (level != null && level.androidStatus >= ThermalLevel.CRITICAL.androidStatus)
            msgs += "Device is at $level: stop gaming and let it cool down before playing."
        return ThermalAdvice(requested, effective, msgs)
    }
}
