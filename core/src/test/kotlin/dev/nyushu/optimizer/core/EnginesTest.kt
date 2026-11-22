package dev.nyushu.optimizer.core

import dev.nyushu.optimizer.core.android.*
import dev.nyushu.optimizer.core.model.*
import dev.nyushu.optimizer.core.monitor.*
import dev.nyushu.optimizer.core.profile.*
import dev.nyushu.optimizer.core.store.*
import dev.nyushu.optimizer.core.switch.*
import dev.nyushu.optimizer.core.universal.*
import org.junit.Assert.*
import org.junit.Test

class EnginesTest {
    private val game = InstalledGame("com.example.game", "Example Game", "1.2.3", 12, 34, 26, true, true, "com.android.vending")
    private val caps = SystemCapabilities(usageAccessGranted = false)
    private val b = AndroidGameOptimizer()

    @Test fun androidNeverClaimsToChangeGameSettings() {
        val a = b.analyze(game, Fixtures.sd870(), OptimizationMode.PERFORMANCE, caps)
        assertTrue(a.recommendations.none { it.applicability != Applicability.USER_ACTION && it.applicability != Applicability.INFORMATIONAL && it.applicability != Applicability.NOT_AVAILABLE })
        assertTrue(a.recommendations.any { it.id == "game-mode-api" && it.applicability == Applicability.NOT_AVAILABLE })
        assertTrue(a.recommendations.any { it.id == "fps" && it.applicability == Applicability.NOT_AVAILABLE })
        assertTrue(a.recommendations.filter { it.id.startsWith("ingame") }.all { it.detail.contains("nothing was changed", true) || it.applicability == Applicability.USER_ACTION })
    }

    @Test fun androidRecommendationsFollowRealState() {
        val low = b.analyze(game, Fixtures.sd870(ramAvail = 900, storageFree = 1000, battery = 10, charging = false), OptimizationMode.BALANCED, caps)
        val ids = low.recommendations.map { it.id }
        assertTrue(ids.containsAll(listOf("ram", "storage", "battery-low", "usage-access")))
        val fine = b.analyze(game, Fixtures.sd870(), OptimizationMode.BALANCED, caps.copy(usageAccessGranted = true))
        val fids = fine.recommendations.map { it.id }
        assertTrue(fids.none { it == "ram" || it == "storage" || it == "battery-low" || it == "usage-access" })
    }

    @Test fun androidDisplayAndGameModeAdvice() {
        val perf = b.analyze(game, Fixtures.sd870(refresh = 60f), OptimizationMode.PERFORMANCE, caps)
        assertTrue(perf.recommendations.any { it.id == "display-hz" && it.title.contains("120") })
        val cool = b.analyze(game, Fixtures.sd870(refresh = 120f), OptimizationMode.COOL, caps)
        assertTrue(cool.recommendations.any { it.id == "display-hz" && it.title.contains("60") })
        val noGm = b.analyze(game.copy(declaresGameModeConfig = false), Fixtures.sd870(), OptimizationMode.BALANCED, caps)
        assertTrue(noGm.recommendations.any { it.id == "game-mode" && it.applicability == Applicability.INFORMATIONAL })
    }

    @Test fun androidThermalDowngrade() {
        val a = b.analyze(game, Fixtures.sd870(thermal = ThermalLevel.SEVERE), OptimizationMode.PERFORMANCE, caps)
        assertEquals(OptimizationMode.COOL, a.effective)
    }

    private fun universal(): Triple<UniversalOptimizer, InMemoryConfigStore, ProfileManager> {
        val st = InMemoryConfigStore(); val pm = ProfileManager(InMemoryProfileStore())
        var t = 5000L
        val sw = SwitchOptimizer(st, InMemoryBackupStore(), clock = { t++ })
        return Triple(UniversalOptimizer(sw, b, pm, clock = { t++ }), st, pm)
    }
    private val monitor = StepResult(PipelineStep.MONITOR, StepStatus.OK, "armed")
    private val sw = GameTarget.Switch(GameRef("0100000000010000", "Test Game", "t"))

    @Test fun universalRoutesSwitchGameAndVerifies() {
        val (u, st, pm) = universal()
        val plan = u.preview(sw, Fixtures.sd870(), OptimizationMode.BALANCED, caps)
        assertNotNull(plan.switchPlan); assertNull(plan.androidAnalysis)
        assertEquals(PipelineStep.entries.size, plan.steps.size)
        val res = u.execute(plan, true, monitor)
        assertEquals(Outcome.APPLIED_AND_VERIFIED, res.outcome)
        assertNotNull(st.files["config/custom/0100000000010000.ini"])
        val prof = pm.get(GameKind.SWITCH, "0100000000010000")!!
        assertEquals(OptimizationMode.BALANCED, prof.mode)
        assertTrue(prof.history.single().success)
    }

    @Test fun universalRoutesAndroidGameWithoutClaimingChanges() {
        val (u, st, pm) = universal()
        val plan = u.preview(GameTarget.Android(game), Fixtures.sd870(), OptimizationMode.PERFORMANCE, caps)
        assertNotNull(plan.androidAnalysis); assertNull(plan.switchPlan)
        val res = u.execute(plan, false, monitor)
        assertEquals(Outcome.PROFILE_SAVED_RECOMMENDATIONS_ONLY, res.outcome)
        assertTrue(res.message.contains("cannot change", true))
        assertTrue(st.files.isEmpty())
        assertNotNull(pm.get(GameKind.ANDROID, "com.example.game"))
    }

    @Test fun universalBlocksSwitchApplyUntilEmulatorClosedConfirmed() {
        val (u, st, pm) = universal()
        val res = u.execute(u.preview(sw, Fixtures.sd870(), OptimizationMode.BALANCED, caps), false, monitor)
        assertEquals(Outcome.BLOCKED, res.outcome)
        assertTrue(st.files.keys.none { it.startsWith("config/custom") })
        assertNull(pm.get(GameKind.SWITCH, sw.id))
    }

    @Test fun universalReportsFailureHonestly() {
        val (u, st, pm) = universal()
        st.failWrites = true
        val res = u.execute(u.preview(sw, Fixtures.sd870(), OptimizationMode.BALANCED, caps), true, monitor)
        assertEquals(Outcome.FAILED, res.outcome)
        assertEquals(StepStatus.FAILED, res.steps.first { it.step == PipelineStep.SAVE }.status)
        val prof = pm.get(GameKind.SWITCH, sw.id)!!
        assertFalse(prof.history.last().success)
    }

    @Test fun universalValidationErrorBlocks() {
        val (u, _, _) = universal()
        val bad = GameTarget.Switch(GameRef("nope", null, "t"))
        val res = u.execute(u.preview(bad, Fixtures.sd870(), OptimizationMode.BALANCED, caps), true, monitor)
        assertEquals(Outcome.BLOCKED, res.outcome)
    }

    @Test fun universalCustomUsesSavedProfileSettings() {
        val (u, st, pm) = universal()
        u.execute(u.preview(sw, Fixtures.sd870(), OptimizationMode.CUSTOM, caps, mapOf("gpu_accuracy" to "1")), true, monitor)
        assertEquals(mapOf("gpu_accuracy" to "1"), pm.get(GameKind.SWITCH, sw.id)!!.customSettings)
        val replay = u.preview(sw, Fixtures.sd870(), OptimizationMode.CUSTOM, caps)
        assertTrue(replay.validationErrors.isEmpty())
        assertTrue(replay.switchPlan!!.alreadySet.any { it.key == "gpu_accuracy" })
    }

    @Test fun recommendedModeReflectsDevice() {
        val (u, _, _) = universal()
        assertEquals(OptimizationMode.COOL, u.recommendMode(Fixtures.sd870(thermal = ThermalLevel.SEVERE)))
        assertEquals(OptimizationMode.BALANCED, u.recommendMode(Fixtures.sd870()))
        assertEquals(OptimizationMode.PERFORMANCE, u.recommendMode(Fixtures.sd870(renderer = "Adreno (TM) 619")))
    }

    @Test fun profileManagerPersistsPerGameAndTrimsHistory() {
        val pm = ProfileManager(InMemoryProfileStore(), maxHistory = 3)
        var p = GameProfile("0100000000010000", GameKind.SWITCH, "Name | with pipe", OptimizationMode.CUSTOM, mapOf("a" to "1"), OptimizationMode.COOL, 5)
        pm.save(p)
        repeat(5) { pm.appendHistory(GameKind.SWITCH, p.id, p.name, HistoryEntry(100L + it, "BALANCED", "line1\nline2 | x", it % 2 == 0)) }
        val got = pm.get(GameKind.SWITCH, p.id)!!
        assertEquals(3, got.history.size); assertEquals(104L, got.history.last().timestampMs)
        assertEquals(mapOf("a" to "1"), got.customSettings); assertEquals(OptimizationMode.COOL, got.recommendedMode)
        assertEquals("Name | with pipe", got.name)
        assertNull(pm.get(GameKind.ANDROID, p.id))
        pm.save(GameProfile("com.x", GameKind.ANDROID, "X", OptimizationMode.COOL))
        assertEquals(2, pm.all().size)
        pm.delete(GameKind.SWITCH, p.id); assertEquals(1, pm.all().size)
    }

    @Test fun metricsBufferKeepsRealValuesAndReasons() {
        val buf = MetricsBuffer(3)
        for (i in 1..5) buf.add(Sample(i.toLong(), mapOf(
            Metric.RAM_USED_PCT to Reading.Value(10.0 * i),
            Metric.GPU_BUSY_PCT to Reading.NotAvailable("sysfs denied"))))
        assertEquals(3, buf.size)
        assertEquals(listOf(30.0, 40.0, 50.0), buf.series(Metric.RAM_USED_PCT))
        assertEquals(40.0, buf.stats(Metric.RAM_USED_PCT)!!.avg, 1e-9)
        assertNull(buf.stats(Metric.GPU_BUSY_PCT)); assertEquals("sysfs denied", buf.unavailableReason(Metric.GPU_BUSY_PCT))
        assertEquals("no sample yet", buf.unavailableReason(Metric.FPS))
    }
}
