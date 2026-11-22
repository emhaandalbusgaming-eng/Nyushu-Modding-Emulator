package dev.nyushu.optimizer.core.device

import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.DeviceTier

data class TierResult(val tier: DeviceTier, val basis: String)

/**
 * Heuristic tiering from the *actual* GPU renderer string and RAM. Nothing here is a benchmark result;
 * the tier only chooses conservative starting points for profiles.
 */
object DeviceClassifier {
    private val ADRENO = Regex("""adreno\D{0,8}(\d{3})""", RegexOption.IGNORE_CASE)
    private val MALI = Regex("""mali[-\s]?g(\d{2,3})""", RegexOption.IGNORE_CASE)

    fun classify(d: DeviceSnapshot): TierResult {
        val renderer = d.gpuRenderer.orNull()
        var tier: DeviceTier
        var basis: String
        val adreno = renderer?.let { ADRENO.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val mali = renderer?.let { MALI.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        when {
            adreno != null -> {
                tier = when { adreno >= 730 -> DeviceTier.FLAGSHIP; adreno >= 640 -> DeviceTier.HIGH; adreno >= 610 -> DeviceTier.MID; else -> DeviceTier.LOW }
                basis = "GPU Adreno $adreno"
            }
            mali != null -> {
                tier = when { mali >= 710 -> DeviceTier.FLAGSHIP; mali in 77..99 -> DeviceTier.HIGH; mali in 72..76 -> DeviceTier.MID; mali >= 100 -> DeviceTier.HIGH; else -> DeviceTier.LOW }
                basis = "GPU Mali-G$mali"
            }
            else -> {
                tier = if (d.ramTotalMb >= 7000 && d.cpuCoreCount >= 8) DeviceTier.MID else DeviceTier.LOW
                basis = "GPU not recognised (${renderer ?: "NOT AVAILABLE"}); estimated from RAM/cores"
            }
        }
        val ramGb = d.ramTotalMb / 1024.0
        if (ramGb < 3.5 && tier.rank > DeviceTier.LOW.rank) { tier = DeviceTier.LOW; basis += "; capped by RAM < 4 GB" }
        else if (ramGb < 5.5 && tier.rank > DeviceTier.MID.rank) { tier = DeviceTier.MID; basis += "; capped by RAM < 6 GB" }
        return TierResult(tier, basis)
    }
}
