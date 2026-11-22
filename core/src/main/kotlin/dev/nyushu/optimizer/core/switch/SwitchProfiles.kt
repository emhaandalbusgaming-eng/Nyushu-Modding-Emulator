package dev.nyushu.optimizer.core.switch

import dev.nyushu.optimizer.core.model.DeviceTier
import dev.nyushu.optimizer.core.model.OptimizationMode

/**
 * Starting points per mode and device tier. These are conservative, explainable heuristics, NOT benchmark results:
 * every value is a key from [SwitchSchema] and every one carries the reason shown to the user.
 * Settings that are not listed here are left untouched (they keep following the emulator's global configuration).
 */
object SwitchProfiles {
    data class Rule(val value: String, val reason: String)

    private fun byTier(t: DeviceTier, flagship: Int, high: Int, mid: Int, low: Int) = when (t) {
        DeviceTier.FLAGSHIP -> flagship; DeviceTier.HIGH -> high; DeviceTier.MID -> mid; DeviceTier.LOW -> low
    }

    fun desired(mode: OptimizationMode, tier: DeviceTier, ramTotalMb: Long): LinkedHashMap<String, Rule> {
        val out = LinkedHashMap<String, Rule>()
        if (mode == OptimizationMode.CUSTOM) return out
        out["use_disk_shader_cache"] = Rule("true", "Keep compiled pipelines on disk so they are not rebuilt every launch")
        out["use_asynchronous_shaders"] = Rule("true", "Compile shaders off the render thread to reduce stutter (may cause brief visual glitches)")
        out["gpu_accuracy"] = Rule("0", "Normal GPU accuracy is the fastest accuracy level; raise only for visual bugs")

        val res = when (mode) {
            OptimizationMode.BALANCED -> byTier(tier, 3, 3, 2, 1)
            OptimizationMode.PERFORMANCE -> byTier(tier, 3, 2, 1, 0)
            else -> byTier(tier, 2, 1, 1, 0)
        }
        out["resolution_setup"] = Rule(res.toString(), "Resolution start point for $tier-tier GPU in $mode mode")

        when (mode) {
            OptimizationMode.BALANCED -> {
                out["anti_aliasing"] = Rule(if (tier.rank >= DeviceTier.HIGH.rank) "1" else "0",
                    if (tier.rank >= DeviceTier.HIGH.rank) "FXAA is cheap on this tier" else "Skip post-process AA on this tier")
                out["max_anisotropy"] = Rule(byTier(tier, 3, 2, 1, 1).toString(), "Texture filtering quality matched to tier")
                out["scaling_filter"] = Rule("1", "Bilinear window filter")
                out["vram_usage_mode"] = Rule("0", "Conservative VRAM use keeps memory headroom for Android")
            }
            OptimizationMode.PERFORMANCE -> {
                out["anti_aliasing"] = Rule("0", "No post-process AA to save GPU time")
                out["max_anisotropy"] = Rule("1", "Default filtering to save GPU time")
                out["use_fast_gpu_time"] = Rule("true", "Prefer speed over GPU timing accuracy")
                out["use_reactive_flushing"] = Rule("false", "Reactive flushing costs performance")
                out["scaling_filter"] = if (res < 3) Rule("5", "FSR recovers sharpness at reduced resolution")
                else Rule("1", "Bilinear window filter")
                val aggressive = ramTotalMb >= 7500 && tier.rank >= DeviceTier.HIGH.rank
                out["vram_usage_mode"] = Rule(if (aggressive) "1" else "0",
                    if (aggressive) "Enough RAM and a capable GPU: allow aggressive VRAM use" else "Not enough RAM/GPU headroom for aggressive VRAM use")
            }
            else -> { // COOL
                out["anti_aliasing"] = Rule("0", "No post-process AA to reduce GPU load")
                out["max_anisotropy"] = Rule("1", "Default filtering to reduce GPU load")
                out["scaling_filter"] = Rule("1", "Bilinear window filter (cheapest upscale)")
                out["use_vsync"] = Rule("2", "FIFO paces presentation and avoids rendering frames the display cannot show")
                out["vram_usage_mode"] = Rule("0", "Conservative VRAM use")
            }
        }
        return out
    }
}
