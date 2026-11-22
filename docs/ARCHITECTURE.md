# Architecture

```
app/ (Android, platform APIs only, no AndroidX)        core/ (pure Kotlin/JVM, unit-tested)
  data/DeviceDetection  (hardware + software)  ─────►  model/DeviceSnapshot, Reading<T> (value or NOT AVAILABLE)
  data/GpuProbe, SystemReaders                          device/DeviceClassifier, ThermalPolicy  (= ThermalManager)
  data/GameScanner, ForegroundDetector,                 switch/SwitchOptimizer  (Engine A) + SwitchSchema, SwitchProfiles, TitleId, Compatibility
       SwitchLibrary            (= GameDetection)       android/AndroidGameOptimizer (Engine B)
  data/SafTree, SafConfigStore  (ConfigStore)           universal/UniversalOptimizer (10-step pipeline)
  data/AppSettings              (= SettingsManager)     profile/ProfileManager
  monitor/MonitorHub, MonitorService (= PerformanceMonitor)   monitor/MetricsBuffer
  ui/* (8 pages)                                        ini/IniDocument, store/* (ConfigStore, BackupStore)
```
Requested module → implementation: DeviceDetection and SoftwareDetection → `DeviceDetection` (one class, hardware and software fields) · GameDetection → `GameScanner` + `SwitchLibrary` + `ForegroundDetector` · ThermalManager → `ThermalPolicy` (+ `DeviceDetection.thermal`) · PerformanceMonitor → `MonitorHub` · SettingsManager → `AppSettings`.

## Engine A — how Nyushu configuration really works
- Per-game file: `<Nyushu user dir>/config/custom/<TITLEID uppercase 16 hex>.ini`; global: `config/config.ini`.
- A per-game override is `key\use_global=false`, `key\default=false`, `key=value` in the section named by the setting's category (`Renderer`, `Cpu`, `Core`, `System`). Source: `frontend_common/config.cpp`, `common/settings.cpp`.
- The user directory is exposed by Nyushu's own DocumentsProvider (`com.andromeda.androbench2.user`, root = `getExternalFilesDir`, ids `root/<path>`), so the companion reaches it through the Storage Access Framework picker, with a persisted grant. No root, no `MANAGE_EXTERNAL_STORAGE`.
- Apply = backup (app-private) → write → **read back and compare** → roll back on any mismatch. Success is reported only after verification.
- Writable keys: the 19 keys verified in both the APK and yuzu-mainline (`tools/verify_schema.py`). Nyushu-only keys (`force_max_clock`, `enable_frame_*`, …) are rejected with a reason.

## Engine B — what Android allows
Another app's graphics settings, Game Mode and process cannot be changed by a normal app, so Engine B returns recommendations tagged `USER_ACTION` / `INFORMATIONAL` / `NOT_AVAILABLE`, with deep links into system settings. "Applied" is never reported for a third-party game.

## Honest data sources (Monitor)
Real: RAM (ActivityManager), battery temp/%, thermal status (PowerManager), battery power (CURRENT_NOW × voltage), CPU frequency and GPU busy (sysfs, **only if the device lets apps read them**), CPU load (`/proc/stat`, blocked on Android 8+ so usually NOT AVAILABLE).
FPS and frame time of other apps: **NOT AVAILABLE** (no unprivileged source exists).
