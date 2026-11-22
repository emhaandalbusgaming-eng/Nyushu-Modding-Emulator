# Stage 2 — Source repository vs. Nyushu 0.1.4 APK

**Verdict: neither provided repository is proven to be the source of the APK. Nothing was merged.**
Method: static comparison only (no builds of the emulator). No `.git` metadata is present in either zip, so **no commit can be identified**.

## What the APK says about itself
Native build paths embedded in `libuzuy-android.so`: `D:/Uzuy-MMJR-master (20.9.24)/uzuy-mmjr/src/...`.
So the APK was built from an "Uzuy-MMJR" tree (version tag 20.9.24 in the folder name), not from either repository below.
Java namespace `org.uzuy.uzuy_emu`, application id `com.andromeda.androbench2`.

## Comparison
| Check | yuzu-mainline-master | OpenSw-thor-lab |
|---|---|---|
| License file | GPL-3.0 (LICENSE.txt) | OpenSw project, SPDX headers "Copyright 2026 OpenSw Emulator Project" |
| Android applicationId / namespace | `org.yuzu.yuzu_emu` | `dev.eden.eden_emulator` (Eden lineage) |
| APK source paths found in repo (622 `src/...` paths, 20 vcpkg `.cxx` build artefacts excluded) | **617 / 622** (missing: caps_a/ss/su/u.cpp, host1x/codecs/decoder.cpp) | 607 / 622 (15 missing incl. common/logging/*, fssystem_pooled_buffer) |
| JNI symbols (APK has 146) | 131 in repo, **all 131 present in APK**; **15 APK-only** (Android NetPlay manager ×12, netPlayInit, frame-interpolation, frame-skipping) | 228 in repo; 142 shared; **4 APK-only**; **86 repo-only** |
| Settings keys (Java enums) | APK is a superset: 21/21 repo Boolean+Int keys present, APK adds 7 Boolean + 6 Int keys (e.g. enable_frame_interpolation, enable_frame_skipping, early_release_fences, sync_core_speed, accelerate_astc, vram_usage_mode, astc_recompression, nvdec_emulation, shader_backend) | not compared key-by-key |
| Submodules | `.gitmodules` lists 22; only 14 of 34 `externals/` dirs are populated → **incomplete, will not build as-is** | no `.gitmodules`; 12 of 13 externals populated |

## Interpretation (what can and cannot be claimed)
- yuzu-mainline is a **plausible ancestor**: it has the best file-path overlap, and every JNI function it defines exists in the APK. The APK adds Android NetPlay, frame interpolation/skipping and other Nyushu-specific code that is not in the zip.
- OpenSw/Thor is **not** a better match: it has far more JNI functions than the APK (86 the APK lacks) and a different application id.
- The APK-specific code (NetPlay JNI, frame interpolation/skipping, `force_max_clock`) cannot be reproduced from either repository, so the emulator cannot be rebuilt from them with identical behaviour.
- Proving a version/commit would need the real Uzuy-MMJR 20.9.24 tree or its git history.

## Consequence for the project
- The companion app does **not** use code from either repository. Only the *file format* and *setting semantics* were read from yuzu-mainline, and every setting is accepted only if it is **also** present in the APK (see `tools/verify_schema.py`: 19/19 keys verified).
- Reproducible evidence lives in `docs/evidence/`.

## Licensing
- yuzu-mainline is GPL-3.0. A modified yuzu-derived APK that is redistributed needs corresponding source under GPL. The licence status of Nyushu itself is unknown.
- The companion app is separate code with no yuzu source copied. Runtime dependency: Kotlin stdlib (Apache-2.0) only; no AndroidX.
