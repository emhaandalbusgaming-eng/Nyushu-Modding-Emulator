package dev.nyushu.optimizer.core.switch

/**
 * Per-game settings Engine A is allowed to write.
 *
 * A key is included ONLY when it was verified in both places:
 *  - APK evidence: key exists in Nyushu 0.1.4 settings model enums (features/settings/model, Boolean/Int/String Setting) and its choice values come from the APK's
 *    `res/values/arrays.xml` (see docs/STAGE2_SOURCE_MATCH.md and tools/verify_schema.py).
 *  - Source evidence: key is a *Switchable* setting in yuzu-mainline common/settings.h, which is what makes the emulator read
 *    `key\use_global=false` from `config/custom/<TitleId>.ini`; the INI section comes from `Settings::TranslateCategory`.
 * Keys that exist only in the APK (force_max_clock, enable_frame_*, early_release_fences, sync_core_speed, optimize_spirv_output)
 * are intentionally absent: their INI section/semantics could not be verified.
 */
enum class ValueKind { BOOL, ENUM_INT }

data class SettingSpec(
    val key: String,
    val section: String,
    val kind: ValueKind,
    val allowed: Map<String, String>,
    val description: String,
) {
    fun isValid(value: String) = value in allowed
    fun label(value: String) = allowed[value] ?: value
}

object SwitchSchema {
    private fun bool(key: String, section: String, desc: String) =
        SettingSpec(key, section, ValueKind.BOOL, linkedMapOf("true" to "On", "false" to "Off"), desc)

    private fun choice(key: String, section: String, desc: String, vararg v: Pair<Int, String>) =
        SettingSpec(key, section, ValueKind.ENUM_INT, v.associate { it.first.toString() to it.second }, desc)

    val specs: List<SettingSpec> = listOf(
        choice("resolution_setup", "Renderer", "Internal resolution scale",
            0 to "0.25x", 1 to "0.5x", 2 to "0.75x", 3 to "1x", 4 to "2x", 5 to "3x", 6 to "4x"),
        choice("scaling_filter", "Renderer", "Window adapting filter",
            0 to "Nearest", 1 to "Bilinear", 2 to "Bicubic", 3 to "Gaussian", 4 to "ScaleForce", 5 to "FSR", 6 to "Area"),
        choice("anti_aliasing", "Renderer", "Anti-aliasing", 0 to "None", 1 to "FXAA", 2 to "SMAA"),
        choice("max_anisotropy", "Renderer", "Anisotropic filtering",
            0 to "Auto", 1 to "Default", 2 to "2x", 3 to "4x", 4 to "8x", 5 to "16x"),
        choice("use_vsync", "Renderer", "Present mode (VSync)",
            0 to "Immediate", 1 to "Mailbox", 2 to "FIFO", 3 to "FIFO relaxed"),
        bool("use_asynchronous_shaders", "Renderer", "Asynchronous shader compilation"),
        bool("use_disk_shader_cache", "Renderer", "Disk shader cache (pipeline cache)"),
        choice("shader_backend", "Renderer", "Shader backend", 0 to "GLSL", 1 to "GLASM", 2 to "SPIR-V"),
        choice("gpu_accuracy", "Renderer", "GPU accuracy", 0 to "Normal", 1 to "High", 2 to "Extreme"),
        bool("use_reactive_flushing", "Renderer", "Reactive flushing"),
        bool("use_fast_gpu_time", "Renderer", "Fast GPU time"),
        choice("nvdec_emulation", "Renderer", "NVDEC emulation", 3 to "Disabled", 1 to "CPU", 2 to "GPU"),
        choice("astc_recompression", "Renderer", "ASTC recompression", 0 to "Uncompressed", 1 to "BC1", 2 to "BC3"),
        choice("accelerate_astc", "Renderer", "ASTC decoding method", 0 to "CPU", 1 to "GPU", 2 to "CPU async"),
        choice("vram_usage_mode", "Renderer", "VRAM usage mode", 0 to "Conservative", 1 to "Aggressive"),
        choice("cpu_accuracy", "Cpu", "CPU accuracy", 0 to "Auto", 1 to "Accurate", 2 to "Unsafe", 3 to "Paranoid"),
        choice("cpu_backend", "Cpu", "CPU backend", 0 to "Dynarmic", 1 to "NCE"),
        bool("use_multi_core", "Core", "Multicore CPU emulation"),
        bool("use_speed_limit", "Core", "Limit speed percent"),
    )

    private val byKey = specs.associateBy { it.key }
    fun find(key: String): SettingSpec? = byKey[key]

    /** Keys seen in Nyushu's Java enums but excluded because section/semantics could not be verified. */
    val unverifiedApkOnlyKeys: Map<String, String> = mapOf(
        "force_max_clock" to "Adreno-only host clock hint implemented inside native code; INI section not verified",
        "enable_frame_interpolation" to "Nyushu-specific; not present in any provided source",
        "enable_frame_skipping" to "Nyushu-specific; not present in any provided source",
        "early_release_fences" to "Not present in provided yuzu-mainline source",
        "sync_core_speed" to "Not present in provided yuzu-mainline source",
        "optimize_spirv_output" to "Not present in provided yuzu-mainline source",
    )

    /** Names of the extra per-game INI suffixes used by the emulator's Config reader. */
    const val USE_GLOBAL = "\\use_global"
    const val DEFAULT = "\\default"
}
