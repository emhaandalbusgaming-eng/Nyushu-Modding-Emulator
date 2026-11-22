# Path B (patching the Nyushu frontend) — evaluation

Nothing was modified in the shipped APK. Backup: `original.apk`, SHA-256
`621965ebf3de2175c9b0529ef8d445337835deb1245e57acd28decd04684e300`.

## Experiment: unmodified round-trip
`apktool d` (full smali) → `apktool b` → zipalign → re-sign with a test key.
- Result: builds; package `com.andromeda.androbench2`, versionName `nyushu 0.1.4`, 3 activities preserved.
- **22 / 22 native libraries byte-identical** to the original.
- Differences: 1054 vs 1153 zip entries (dropped `META-INF/*.version` files; resource paths un-obfuscated), size 50.9 MB vs 55.0 MB.
- NOT TESTED: installation and runtime on a device.

## What patching could add, and what it would cost
| Idea | Needed change | Verdict |
|---|---|---|
| Read/write per-game config | none: done through Nyushu's own DocumentsProvider (SAF) | **no patch needed** |
| Launch a game by Title ID from the companion | `EmulationActivity` takes a parcelable `Game` extra via a navigation graph; a new exported entry point would have to build that `Game` in smali | possible but invasive; not attempted |
| FPS / frame-time source | would require hooks inside the native renderer | not possible at smali level |
| CPU/GPU frequency control | none exists in the APK to extend | out of scope |

## Impact if patching is ever done
- **Signature changes.** Updates over the installed APK fail; the user must uninstall, which deletes `Android/data/com.andromeda.androbench2` (games' saves, config, shaders unless backed up first).
- **Licence.** Redistribution of a modified yuzu-derived APK carries GPL-3.0 source obligations; the true source tree is not available.
- **Ads SDK remnants** (Audience Network, iads) ship in the APK; a patch would be a chance to remove them, but they appear dormant (no manifest activities/AD_ID).

**Recommendation:** do not patch. Path A already covers Engine A via SAF without touching the emulator.
