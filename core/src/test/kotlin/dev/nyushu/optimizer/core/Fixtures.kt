package dev.nyushu.optimizer.core

import dev.nyushu.optimizer.core.model.DeviceSnapshot
import dev.nyushu.optimizer.core.model.Reading
import dev.nyushu.optimizer.core.model.ThermalLevel

/** Test data only: a Snapdragon-870-class phone described by values, not by any special case in the code under test. */
object Fixtures {
    fun sd870(
        renderer: String? = "Adreno (TM) 650",
        ramTotalMb: Long = 7800,
        thermal: ThermalLevel? = ThermalLevel.NONE,
        batteryTemp: Float? = 33f,
        refresh: Float = 120f,
        supported: List<Float> = listOf(60f, 120f),
        ramAvail: Long = 3500,
        storageFree: Long = 40_000,
        battery: Int? = 80,
        charging: Boolean? = false,
    ) = DeviceSnapshot(
        manufacturer = "TestCo", model = "SD870-class",
        socName = Reading.Value("SM8250-AC"), abis = listOf("arm64-v8a"), cpuCoreCount = 8,
        cpuMaxFreqMhz = Reading.Value(3200),
        gpuRenderer = renderer?.let { Reading.Value(it) } ?: Reading.NotAvailable("EGL unavailable"),
        gpuVendor = Reading.Value("Qualcomm"), glEsVersion = Reading.Value("OpenGL ES 3.2"),
        vulkanApiVersion = Reading.Value("1.1"),
        ramTotalMb = ramTotalMb, ramAvailMb = ramAvail, storageTotalMb = 128_000, storageFreeMb = storageFree,
        displayRefreshHz = refresh, supportedRefreshHz = supported,
        androidRelease = "14", sdkInt = 34, kernel = "5.4.x",
        thermal = thermal?.let { Reading.Value(it) } ?: Reading.NotAvailable("API unavailable"),
        batteryTempC = batteryTemp?.let { Reading.Value(it) } ?: Reading.NotAvailable("no battery"),
        batteryPercent = battery?.let { Reading.Value(it) } ?: Reading.NotAvailable("n/a"),
        charging = charging?.let { Reading.Value(it) } ?: Reading.NotAvailable("n/a"),
        emulatorVersion = Reading.Value("nyushu 0.1.4"),
    )
}
