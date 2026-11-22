#!/usr/bin/env python3
"""Cross-checks core SwitchSchema.kt against evidence extracted from the Nyushu 0.1.4 APK and yuzu-mainline source.

Usage: verify_schema.py <SwitchSchema.kt> <jadx_sources_dir> <apktool_out_dir> <yuzu_settings.h>
Exit code 1 if any schema key/value is not backed by evidence."""
import re, sys, pathlib

schema_kt, jadx, apktool, settings_h = map(pathlib.Path, sys.argv[1:5])
kt = schema_kt.read_text()
specs = []  # (key, section, {value})
for m in re.finditer(r'(bool|choice)\("([a-z_0-9]+)",\s*"([A-Za-z]+)"(.*?)\)\s*,\n', kt, re.S):
    kind, key, section, rest = m.groups()
    vals = {"true", "false"} if kind == "bool" else set(re.findall(r'(\d+)\s+to\s+"', rest))
    specs.append((key, section, vals))

# 1. keys in Nyushu Java enums
apk_keys = set()
for f in (jadx / "org/uzuy/uzuy_emu/features/settings/model").glob("*Setting.java"):
    apk_keys |= set(re.findall(r'\("([a-z_0-9]+)"', f.read_text()))

# 2. choice arrays in APK resources
arrays = {}
x = (apktool / "res/values/arrays.xml").read_text()
for m in re.finditer(r'<(?:string|integer)-array name="([^"]+)">(.*?)</(?:string|integer)-array>', x, re.S):
    arrays[m.group(1)] = set(re.findall(r'<item>([^<@]*)</item>', m.group(2))) - {""}
ARRAY_FOR = {
    "resolution_setup": "rendererResolutionValues", "scaling_filter": "rendererScalingFilterValues",
    "anti_aliasing": "rendererAntiAliasingValues", "max_anisotropy": "anisoValues", "use_vsync": "rendererVSyncValues",
    "shader_backend": "rendererShaderValues", "gpu_accuracy": "rendererAccuracyValues", "nvdec_emulation": "rendererNvdecValues",
    "astc_recompression": "astcRecompressionMethodValues", "accelerate_astc": "astcDecodingMethodValues",
    "vram_usage_mode": "vramUsageMethodValues", "cpu_accuracy": "cpuAccuracyValues", "cpu_backend": "cpuBackendArm64Values",
}

# 3. yuzu-mainline settings.h: switchable + category
h = settings_h.read_text()
CAT = {"Renderer": "Renderer", "RendererAdvanced": "Renderer", "Cpu": "Cpu", "CpuDebug": "Cpu", "Core": "Core", "System": "System"}

bad = 0
print(f"{'key':26} {'APK enum':9} {'APK values':11} {'yuzu switchable':16} {'section ok':10}")
for key, section, vals in specs:
    in_apk = key in apk_keys
    arr = ARRAY_FOR.get(key)
    if arr is None:
        vals_ok = "n/a(bool)" if vals == {"true", "false"} else "NO ARRAY"
        v_good = vals == {"true", "false"}
    else:
        v_good = arrays.get(arr) == vals
        vals_ok = "match" if v_good else f"DIFF {sorted(arrays.get(arr, []))} vs {sorted(vals)}"
    m = re.search(r'SwitchableSetting<[^;]*?"%s"[^;]*?;' % key, h, re.S)
    sw = bool(m)
    cat = re.search(r'Category::(\w+)', m.group(0)).group(1) if m else None
    sec_ok = cat is not None and CAT.get(cat) == section
    ok = in_apk and v_good and sw and sec_ok
    bad += not ok
    print(f"{key:26} {str(in_apk):9} {vals_ok:11} {str(sw):16} {str(sec_ok):10} {'' if ok else '<== NOT VERIFIED'}")
print(f"\n{len(specs)} schema keys, {bad} not fully verified")
sys.exit(1 if bad else 0)
