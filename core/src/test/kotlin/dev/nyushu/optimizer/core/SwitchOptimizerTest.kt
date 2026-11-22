package dev.nyushu.optimizer.core

import dev.nyushu.optimizer.core.ini.IniDocument
import dev.nyushu.optimizer.core.model.*
import dev.nyushu.optimizer.core.store.*
import dev.nyushu.optimizer.core.switch.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SwitchOptimizerTest {
    private val id = "0100000000010000"
    private val game = GameRef(id, "Test Game", "test")
    private lateinit var store: InMemoryConfigStore
    private lateinit var backups: InMemoryBackupStore
    private lateinit var opt: SwitchOptimizer
    private var now = 1000L

    @Before fun setUp() {
        store = InMemoryConfigStore()
        backups = InMemoryBackupStore()
        opt = SwitchOptimizer(store, backups, clock = { now++ })
        store.files["config/config.ini"] = "[Renderer]\nresolution_setup=4\nresolution_setup\\default=false\n[GpuDriver]\ndriver_path=/data/drivers/turnip.zip\n"
    }

    private fun ini() = IniDocument.parse(store.files[SwitchOptimizer.customPath(id)]!!)

    @Test fun planReadsGlobalValuesDriverAndMarksCompatibilityUnknown() {
        val p = opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED)
        assertTrue(p.validation.ok)
        val res = p.changes.first { it.key == "resolution_setup" }
        assertEquals("4", res.before); assertEquals("global", res.beforeSource); assertEquals("3", res.after)
        assertEquals("/data/drivers/turnip.zip", p.gpuDriver)
        assertEquals(CompatStatus.UNKNOWN, p.compatibility.status)
        assertFalse(p.configExists)
    }

    @Test fun applyWritesEmulatorLayoutAndVerifies() {
        val p = opt.plan(game, Fixtures.sd870(), OptimizationMode.PERFORMANCE)
        val r = opt.apply(p)
        assertTrue(r is ApplyResult.Applied)
        val d = ini()
        for (c in p.changes) {
            assertEquals("false", d.get(c.section, c.key + "\\use_global"))
            assertEquals("false", d.get(c.section, c.key + "\\default"))
            assertEquals(c.after, d.get(c.section, c.key))
        }
        assertEquals("2", d.get("Renderer", "resolution_setup")) // HIGH tier, performance
        assertEquals("5", d.get("Renderer", "scaling_filter"))   // FSR when below 1x
    }

    @Test fun applyIsIdempotent() {
        opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED))
        val second = opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED)
        assertTrue(second.changes.isEmpty()); assertTrue(second.alreadySet.isNotEmpty())
        assertTrue(opt.apply(second) is ApplyResult.NoChange)
    }

    @Test fun preservesUnknownKeysAndUserSettings() {
        val path = SwitchOptimizer.customPath(id)
        store.files[path] = "; mine\n[Controls]\nplayer_0_type=3\n[Renderer]\nfuture_key=zzz\n"
        opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.COOL))
        val out = store.files[path]!!
        assertTrue(out.contains("; mine")); assertTrue(out.contains("player_0_type=3")); assertTrue(out.contains("future_key=zzz"))
    }

    @Test fun backupHoldsOriginalAndRollbackRestoresIt() {
        val path = SwitchOptimizer.customPath(id)
        val original = "[Renderer]\nresolution_setup\\use_global=false\nresolution_setup\\default=false\nresolution_setup=6\n"
        store.files[path] = original
        assertTrue(opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.COOL)) is ApplyResult.Applied)
        assertNotEquals(original, store.files[path])
        assertEquals(original, backups.latest(id)!!.content)
        assertTrue(opt.rollbackLast(id) is ApplyResult.Applied)
        assertEquals(original, store.files[path])
    }

    @Test fun rollbackOfNewFileDeletesIt() {
        assertTrue(opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED)) is ApplyResult.Applied)
        assertNull(backups.latest(id)!!.content)
        opt.rollbackLast(id)
        assertNull(store.files[SwitchOptimizer.customPath(id)])
    }

    @Test fun failedWriteLeavesOriginalAndReportsRollback() {
        val path = SwitchOptimizer.customPath(id)
        store.files[path] = "[Renderer]\nx=1\n"
        store.failWrites = true
        val r = opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED))
        assertTrue(r is ApplyResult.Failed)
        assertEquals("[Renderer]\nx=1\n", store.files[path])
    }

    @Test fun silentCorruptionIsDetectedAndRolledBack() {
        val path = SwitchOptimizer.customPath(id)
        store.files[path] = "[Renderer]\nx=1\n"
        store.corruptWrites = true
        val r = opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED))
        assertTrue(r is ApplyResult.Failed)
        assertTrue((r as ApplyResult.Failed).reason.contains("Read-back"))
    }

    @Test fun thermalDowngradeChangesAppliedValues() {
        val cool = opt.plan(game, Fixtures.sd870(thermal = ThermalLevel.SEVERE), OptimizationMode.PERFORMANCE)
        assertEquals(OptimizationMode.COOL, cool.effective)
        assertEquals("1", cool.changes.first { it.key == "resolution_setup" }.after)
        assertTrue(cool.thermal.downgraded)
    }

    @Test fun invalidTitleIdIsBlocked() {
        val p = opt.plan(GameRef("XYZ", null, "t"), Fixtures.sd870(), OptimizationMode.BALANCED)
        assertFalse(p.validation.ok)
        assertTrue(opt.apply(p) is ApplyResult.Blocked)
        assertTrue(store.files.keys.none { it.startsWith("config/custom") })
    }

    @Test fun customRejectsUnsupportedAndInvalidSettings() {
        val p = opt.plan(game, Fixtures.sd870(), OptimizationMode.CUSTOM,
            mapOf("force_max_clock" to "true", "resolution_setup" to "99", "made_up_key" to "1", "gpu_accuracy" to "1"))
        assertFalse(p.validation.ok)
        assertEquals(3, p.validation.errors.size)
        assertTrue(p.validation.errors.any { it.contains("force_max_clock") && it.contains("not supported") })
        assertTrue(opt.apply(p) is ApplyResult.Blocked)
    }

    @Test fun customWritesOnlyChosenKeys() {
        val p = opt.plan(game, Fixtures.sd870(), OptimizationMode.CUSTOM, mapOf("gpu_accuracy" to "1", "use_multi_core" to "true"))
        assertTrue(opt.apply(p) is ApplyResult.Applied)
        val d = ini()
        assertEquals("1", d.get("Renderer", "gpu_accuracy")); assertEquals("true", d.get("Core", "use_multi_core"))
        assertNull(d.get("Renderer", "resolution_setup"))
    }

    @Test fun customWithoutSettingsIsInvalid() {
        assertFalse(opt.plan(game, Fixtures.sd870(), OptimizationMode.CUSTOM).validation.ok)
    }

    @Test fun resetToGlobalReturnsControlToEmulatorGlobals() {
        opt.apply(opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED))
        val r = opt.resetToGlobal(id)
        assertTrue(r is ApplyResult.Applied)
        val d = ini()
        assertEquals("true", d.get("Renderer", "resolution_setup\\use_global"))
        assertNull(d.get("Renderer", "resolution_setup"))
        assertNull(d.get("Renderer", "resolution_setup\\default"))
        val again = opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED)
        assertTrue(again.changes.isNotEmpty())
        assertTrue(opt.resetToGlobal("0100000000099000") is ApplyResult.NoChange)
    }

    @Test fun missingGlobalConfigIsReportedNotInvented() {
        store.files.remove("config/config.ini")
        val p = opt.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED)
        assertTrue(p.validation.warnings.any { it.contains("Global config.ini") })
        assertNull(p.changes.first().before)
        assertEquals("emulator default", p.changes.first().beforeSource)
    }

    @Test fun gpuUnavailableProducesWarningNotCrash() {
        val p = opt.plan(game, Fixtures.sd870(renderer = null), OptimizationMode.BALANCED)
        assertTrue(p.validation.ok); assertTrue(p.validation.warnings.any { it.contains("GPU renderer NOT AVAILABLE") })
    }

    @Test fun fileBackedStoresWork() {
        val dir = java.nio.file.Files.createTempDirectory("nyushu").toFile()
        val fs = FileConfigStore(dir); val fb = FileBackupStore(java.io.File(dir, "bk"))
        val o = SwitchOptimizer(fs, fb)
        assertTrue(o.apply(o.plan(game, Fixtures.sd870(), OptimizationMode.BALANCED)) is ApplyResult.Applied)
        assertNotNull(fs.read(SwitchOptimizer.customPath(id)))
        assertNull(fb.latest(id)!!.content)
        assertEquals(listOf("$id.ini"), fs.list("config/custom"))
        assertTrue(runCatching { fs.read("../escape") }.isFailure || fs.read("../escape") == null)
        dir.deleteRecursively()
    }
}
