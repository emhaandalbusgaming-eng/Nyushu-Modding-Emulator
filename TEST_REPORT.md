# Test report — Nyushu Universal Gaming Optimizer 0.1.0

Statuses: PASS · PARTIAL · FAIL · NOT TESTED. **No device was available; nothing below ran on a POCO F4.** No benchmark was run and none is claimed.

## What was actually executed
- `tools/run_core_tests.sh` → kotlinc 2.0.21 + JUnit 4.13.2: **48 tests, 0 failures** (same sources/tests as `./gradlew :core:test`, which was **not run**: Google/Maven repositories are unreachable from the build sandbox).
- `tools/verify_schema.py` → **19/19** schema keys backed by the APK's Java enums + choice arrays and by yuzu-mainline's switchable settings/categories.
- **Mutation check:** 5 deliberate bugs (no read-back verification, thermal never downgrades, wrong `use_global`, Android recommendation mislabelled as applicable, no backup before write) → each made at least one test fail.
- Android layer compiled against `android.jar` (API 35) with zero errors; `tools/build_local.sh` produced an APK (1.8 MB) that `apksigner verify` accepts and `aapt dump badging` reads (package `dev.nyushu.optimizer`, minSdk 29, targetSdk 35, 208 app classes in `classes.dex`).

## A. APK
| Item | Status | Notes |
|---|---|---|
| Build via Gradle/AGP | NOT TESTED | Workflow in `.github/workflows/build.yml`; needs GitHub Actions or a machine with SDK 35 |
| Build via `tools/build_local.sh` | PASS | Uses a slightly adjusted manifest for the old aapt (FGS type dataSync, no `<property>`); not the release artifact |
| Signing | PASS | v3 verified with a generated **debug** key |
| Installation | NOT TESTED | |
| Startup | NOT TESTED | |

## B. Device detection
| Item | Status | Notes |
|---|---|---|
| Tier classification from GPU string/RAM (no hardcoded device) | PASS | unit tests incl. "same device, different GPU string → different tier" |
| Hardware / software / Vulkan / thermal readers | NOT TESTED | compile-checked only; unreadable values become `NOT AVAILABLE` by design |

## C. Switch (Engine A)
| Item | Status | Notes |
|---|---|---|
| Title ID parsing & kinds | PASS | unit |
| Library from per-game configs / shader dirs / file names / manual | NOT TESTED | SAF code compiled, never run against Nyushu's provider |
| Read current config, plan, validate | PASS | unit, in-memory and file-backed stores |
| Write per-game INI in emulator layout, read-back verify | PASS (against our model of the format) / NOT TESTED (inside Nyushu) | format taken from yuzu-mainline source; the emulator itself was not run |
| Idempotence, preserves unknown keys/comments | PASS | unit |
| Reset to global, roll back last change | PASS | unit |
| Reject unsupported/invalid settings | PASS | unit (`force_max_clock`, bad values, bad Title ID) |
| Compatibility status | PASS | `UNKNOWN` unless a CSV is imported; never inferred |
| Profile persistence | PASS | unit |
| Game launch | FAIL (not possible) | `EmulationActivity` needs a parcelable `Game`; the app can only open Nyushu and says so |

## D. Android (Engine B)
| Item | Status | Notes |
|---|---|---|
| Recommendation logic, never claims to change a game | PASS | unit |
| Game scanning / package info / foreground detection | NOT TESTED | compiled only |
| Profile management | PASS | unit |
| Game Mode integration | NOT AVAILABLE | setting another app's Game Mode needs a system permission; app shows this explicitly |

## E. Stability
| Item | Status | Notes |
|---|---|---|
| Configuration rollback | PASS | failed write, silent corruption, and backup restore, all unit-tested |
| Crash recovery | PARTIAL | backup is taken before every write and survives a crash, but there is no automatic recovery pass at startup |
| Thermal policy | PASS (logic) / NOT TESTED (live sensors) | |
| Memory stability | NOT TESTED | |
| Performance Monitor graphs | NOT TESTED | FPS/frame time are NOT AVAILABLE by design |

## Known risks before first device run
UI has never been displayed. Nyushu's provider root title and the SAF picker flow are unverified. The `<queries>` entry assumes Nyushu's default package unless Settings changes it. On Android 13+ the monitor notification needs the notification permission to be visible.
