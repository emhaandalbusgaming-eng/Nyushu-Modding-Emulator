# Nyushu Universal Gaming Optimizer (companion app)

Two independent engines behind one pipeline:
- **Engine A** edits Nyushu's per-game INI (`config/custom/<TitleID>.ini`) through the Storage Access Framework, with backup, read-back verification and rollback.
- **Engine B** analyzes native Android games and gives recommendations. It cannot change another app's settings and never says it did.

Target: POCO F4 (Snapdragon 870 / Adreno 650), but nothing is hardcoded to it: tier and limits come from the values the device reports. Works without root; anything Android hides is shown as NOT AVAILABLE.

## Build
**GitHub Actions (recommended):** push this folder to a repository; `.github/workflows/build.yml` runs the core tests, builds debug and release APKs, and uploads them with SHA-256 sums. Optional secrets for a real release signature: `NYUSHU_KEYSTORE_B64`, `NYUSHU_KEYSTORE_PASSWORD`, `NYUSHU_KEY_ALIAS`, `NYUSHU_KEY_PASSWORD`. Without them the release APK is signed with the debug key (installable, not for distribution).

**Locally with Android SDK 35 + JDK 17:** `./gradlew :core:test :app:assembleRelease` → `app/build/outputs/apk/release/`.

**Without Gradle (proof build only):** `tools/build_local.sh` (needs kotlinc, android.jar API 35 + API 29, aapt, dalvik-exchange, zipalign, apksigner).
**Tests without Gradle:** `tools/run_core_tests.sh`.

## Install
`adb install -r nyushu-optimizer-*.apk` or open the APK on the phone (allow installs from the file manager).

## First run
1. Settings → *Nyushu data folder* → *Choose folder*; in the system picker open the Nyushu storage entry and press *Use this folder*. The app refuses any other provider.
2. Optional: choose your games folder (file names containing `[TitleID]` are used; files are never opened), import a compatibility CSV (`TitleID,status,note`), grant Usage Access for active-game detection.
3. Switch Games / Android Games → **Auto optimize** → *Analyze* → *Apply*. For Switch games, close Nyushu completely first and tick the confirmation. Settings take effect the next time the game starts.
4. Undo: *Roll back last change* or *Reset to global* on the result card.

## Limits (by design)
No FPS/frame-time for other apps, no CPU/GPU overclock, no voltage or thermal changes, no root commands, no game launch by Title ID (Nyushu does not allow it), no compatibility guesses.
See `TEST_REPORT.md` — nothing has been run on a device yet.
