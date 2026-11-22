package dev.nyushu.optimizer.core

import dev.nyushu.optimizer.core.device.DeviceClassifier
import dev.nyushu.optimizer.core.device.ThermalPolicy
import dev.nyushu.optimizer.core.model.*
import dev.nyushu.optimizer.core.switch.*
import org.junit.Assert.*
import org.junit.Test

class SchemaAndDeviceTest {
    @Test fun schemaHasNoUnverifiedKeys() {
        for (k in SwitchSchema.unverifiedApkOnlyKeys.keys) assertNull("$k must not be writable", SwitchSchema.find(k))
        assertEquals(SwitchSchema.specs.size, SwitchSchema.specs.map { it.key }.toSet().size)
        assertTrue(SwitchSchema.specs.all { it.section in setOf("Renderer", "Cpu", "Core") })
    }

    @Test fun profilesOnlyUseSchemaKeysWithValidValues() {
        for (mode in listOf(OptimizationMode.BALANCED, OptimizationMode.PERFORMANCE, OptimizationMode.COOL))
            for (tier in DeviceTier.entries) for (ram in listOf(3000L, 6000L, 12000L))
                for ((k, rule) in SwitchProfiles.desired(mode, tier, ram)) {
                    val spec = SwitchSchema.find(k)
                    assertNotNull("$k missing from schema", spec)
                    assertTrue("$k=${rule.value} invalid ($mode/$tier)", spec!!.isValid(rule.value))
                    assertTrue(rule.reason.isNotBlank())
                }
    }

    @Test fun neverAutoSelectsUnsafeAccuracy() {
        for (mode in OptimizationMode.entries) for (tier in DeviceTier.entries) {
            val d = SwitchProfiles.desired(mode, tier, 8000)
            assertNull(d["cpu_accuracy"]); assertNull(d["cpu_backend"]); assertNotEquals("2", d["gpu_accuracy"]?.value)
        }
    }

    @Test fun performanceNeverUsesMoreResolutionThanBalanced() {
        for (tier in DeviceTier.entries) {
            val b = SwitchProfiles.desired(OptimizationMode.BALANCED, tier, 8000)["resolution_setup"]!!.value.toInt()
            val p = SwitchProfiles.desired(OptimizationMode.PERFORMANCE, tier, 8000)["resolution_setup"]!!.value.toInt()
            val c = SwitchProfiles.desired(OptimizationMode.COOL, tier, 8000)["resolution_setup"]!!.value.toInt()
            assertTrue(p <= b); assertTrue(c <= b)
        }
    }

    @Test fun classifierReadsGpuString() {
        assertEquals(DeviceTier.HIGH, DeviceClassifier.classify(Fixtures.sd870()).tier)
        assertEquals(DeviceTier.FLAGSHIP, DeviceClassifier.classify(Fixtures.sd870(renderer = "Adreno (TM) 740", ramTotalMb = 12000)).tier)
        assertEquals(DeviceTier.LOW, DeviceClassifier.classify(Fixtures.sd870(renderer = "Adreno (TM) 505")).tier)
        assertEquals(DeviceTier.HIGH, DeviceClassifier.classify(Fixtures.sd870(renderer = "Mali-G78 MP14")).tier)
    }

    @Test fun classifierDoesNotHardcodeTheTargetDevice() {
        // Same "device", different GPU string => different tier. Proves tier comes from the reading.
        val a = DeviceClassifier.classify(Fixtures.sd870(renderer = "Adreno (TM) 650")).tier
        val b = DeviceClassifier.classify(Fixtures.sd870(renderer = "Adreno (TM) 619")).tier
        assertNotEquals(a, b)
    }

    @Test fun classifierFallsBackWhenGpuUnknownAndCapsByRam() {
        val unknown = DeviceClassifier.classify(Fixtures.sd870(renderer = null))
        assertTrue(unknown.basis.contains("not recognised"))
        assertEquals(DeviceTier.MID, DeviceClassifier.classify(Fixtures.sd870(ramTotalMb = 5000)).tier)
        assertEquals(DeviceTier.LOW, DeviceClassifier.classify(Fixtures.sd870(ramTotalMb = 3000)).tier)
    }

    @Test fun thermalPolicyDowngradesButNeverUpgrades() {
        val hot = ThermalPolicy.evaluate(OptimizationMode.PERFORMANCE, Reading.Value(ThermalLevel.SEVERE), Reading.Value(35f))
        assertEquals(OptimizationMode.COOL, hot.effective); assertTrue(hot.downgraded)
        val mod = ThermalPolicy.evaluate(OptimizationMode.PERFORMANCE, Reading.Value(ThermalLevel.MODERATE), Reading.Value(35f))
        assertEquals(OptimizationMode.BALANCED, mod.effective)
        val coolAlready = ThermalPolicy.evaluate(OptimizationMode.COOL, Reading.Value(ThermalLevel.NONE), Reading.Value(30f))
        assertEquals(OptimizationMode.COOL, coolAlready.effective)
        val bal = ThermalPolicy.evaluate(OptimizationMode.BALANCED, Reading.Value(ThermalLevel.NONE), Reading.Value(30f))
        assertEquals(OptimizationMode.BALANCED, bal.effective)
    }

    @Test fun thermalPolicyBatteryTempAndCustomAndUnavailable() {
        assertEquals(OptimizationMode.COOL, ThermalPolicy.evaluate(OptimizationMode.PERFORMANCE, Reading.Value(ThermalLevel.NONE), Reading.Value(46f)).effective)
        assertEquals(OptimizationMode.BALANCED, ThermalPolicy.evaluate(OptimizationMode.PERFORMANCE, Reading.Value(ThermalLevel.NONE), Reading.Value(43f)).effective)
        val custom = ThermalPolicy.evaluate(OptimizationMode.CUSTOM, Reading.Value(ThermalLevel.SEVERE), Reading.Value(30f))
        assertEquals(OptimizationMode.CUSTOM, custom.effective); assertTrue(custom.messages.isNotEmpty())
        val na = ThermalPolicy.evaluate(OptimizationMode.PERFORMANCE, Reading.NotAvailable("x"), Reading.NotAvailable("y"))
        assertEquals(OptimizationMode.PERFORMANCE, na.effective)
        assertTrue(na.messages.any { it.contains("NOT AVAILABLE") })
    }

    @Test fun compatibilityIsUnknownUnlessImported() {
        assertEquals(CompatStatus.UNKNOWN, CompatibilityDb().lookup("0100000000010000").status)
        val db = CompatibilityDb.parseCsv("# c\n0100000000010000,playable,runs fine\nbad,line\n0100000000020000,Wont Boot\n")
        assertEquals(2, db.size)
        assertEquals(CompatStatus.PLAYABLE, db.lookup("0100000000010000").status)
        assertEquals(CompatStatus.PLAYABLE, db.lookup("0100000000010800").status) // update id falls back to base
        assertEquals(CompatStatus.WONT_BOOT, db.lookup("0100000000020000").status)
        assertEquals(CompatStatus.UNKNOWN, db.lookup("nonsense").status)
    }
}
