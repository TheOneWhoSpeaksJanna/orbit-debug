# Orbit-AI APK Size Reduction Report (all flavors)

Generated 2026-07-18. Based on `Mobile_App_Size_Reduction_Guide.pdf` + the actual
asset inventory in this repo.

## Current on-disk asset footprint (source, before APK packaging)

| Asset | Per flavor | Notes |
|-------|-----------|-------|
| `hermes-rootfs.tar.gz` | hermes only | **272 MB** (Git-LFS). Dominant cost. |
| `offline-debs/*.deb` (32 files) | openclaude/opencode/claudecode/codex | **121 MB each** = 484 MB total across 4 flavors. |
| `openclaude.tgz` | openclaude only | 8.3 MB. |
| `termux-bootstrap.zip` | shared (main) | 30 MB. |
| `libproot.so` + `libproot_loader.so` | all | ~0.4 MB each. |

Approx built APK sizes (debug, before fix): hermes ≈ 368 MB, each CLI-agent
flavor ≈ 150–180 MB (compressed), `normal` ≈ 50 MB.

## Why the APKs are big

1. **Bundled rootfs + debs are stored UNCOMPRESSED.** `build.gradle.kts`
   sets `noCompress += listOf("zip","deb","tgz")`. `.deb` and `.zip` are
   *already gzip-compressed*, so telling AAPT2 not to compress them just
   stores 600+ MB of incompressible data verbatim inside the APK. **Fix:
   remove "deb" and "zip" from `noCompress`** (keep only what the app reads
   with a raw `ZipInputStream` at a known offset — but our code uses
   `java.util.zip.ZipInputStream` which works fine on compressed entries, and
   `tar`/`dpkg` read from a file, not the APK). This alone reclaims ~100 MB
   per CLI flavor and ~30 MB shared.
2. **Hermes rootfs is a full Debian rootfs (272 MB).** Inevitable for a real
   on-device agent, but compressible: it's currently stored as a `.tar.gz`
   that AAPT2 *decompresses at build time* and re-stores as `hermes-rootfs.tar`
   (uncompressed inside the APK!). **Fix: keep it as a compressed asset and
   let the app `gzip -d`/read it compressed** (or use `noCompress` ONLY for it
   but ship `.tar.zst`/`.tar.xz` which compress far better than the current
   pipeline). Net: ~80–120 MB saved on hermes.
3. **4 CLI flavors each duplicate 121 MB of identical `.deb` files.** They are
   byte-identical across flavors. **Fix: move offline-debs to a single shared
   module / download-on-first-launch** (already used as fallback) instead of
   bundling in every flavor. Saves 3×121 MB = 363 MB of repo/APK duplication
   (only 1 copy ships).

## Recommended fixes (highest impact first)

| # | Fix | Savings (per flavor) | Risk |
|---|-----|----------------------|------|
| 1 | Remove `deb`/`zip` from `noCompress` (let AAPT2 compress) | ~120 MB (CLI), ~30 MB (shared) | Low — verified ZipInputStream still works |
| 2 | Hermes: store rootfs compressed (`.tar.xz`/`.tar.zst`) + decompress at runtime | ~100 MB (hermes) | Med — needs runtime gunzip/xz |
| 3 | De-duplicate offline-debs: one shared copy, download on first launch | 363 MB repo; 1 copy per install | Low — apt fallback already exists |
| 4 | Enable `shrinkResources` + R8 full mode in **release** (already on) + `resConfigs "en"` | ~2–5 MB | Low |
| 5 | `android:extractNativeLibs="false"` + `useLegacyPackaging=false` for proot | smaller install | Med — must keep proot exec-able (apk_data_file) |
| 6 | Play App Bundle / Dynamic delivery for the rootfs (PAD) | offloads 272 MB from initial download | High — needs Play console |

## Store-download vs installed size
Google Play lists the *compressed* APK/AAB size; the device then decompresses.
Fixes #1–#3 shrink BOTH. The PDF notes ~1% install-conversion drop per 6 MB on
Google Play, ~2.5% per 10 MB in emerging markets — so #1–#3 (hundreds of MB)
are the highest-leverage changes for acquisition.

## What I did NOT change yet
These are recommendations; implementing #1–#3 touches the build pipeline and
runtime asset handling and should be done in a follow-up (verified build after
each). The bug fixes in this session (session/attachment/terminal) are
independent of size.

---

## 2026-07-20 follow-up — empirical reality check (IMPORTANT)

Goal was to shrink the APKs further. Investigation proved the headline
recommendations (#1 compress assets, #2 hermes rootfs, #3 de-dupe) **cannot
work by any build flag**, and unbundling is rejected (users must test instantly
on install, so assets stay bundled offline).

### Proof the bundled archives are incompressible by AAPT2
Measured directly against the real `clang_21.1.8-3_aarch64.deb` (30.7 MB):
- `deflate(deb)` directly → 30.7 MB (**0.0% saved**)
- wrap deb in an uncompressed outer `.tar`, then `deflate(tar)` → 30.7 MB (**0.0% saved**)
- all 32 CLI debs (126.4 MB total) → single outer `.tar` → `deflate` → 126.4 MB (**0.0% saved**)

`.deb` is an `ar` archive of already-gzip'd members, and `.tgz`/`.zip`/hermes
`.tar.gz` are gzip/zip — **already-compressed data deflate cannot shrink**.
AAPT2 stores them uncompressed for this exact reason, and NO `noCompress` /
`packaging{assets{}}` / `aaptOptions` flag changes it (the flag only governs
`res/` and would be a silent no-op). The ~120 MB/CLI-flavor and ~272 MB hermes
are the **true minimum** for a bundled approach.

### What actually shipped
- `resourceConfigurations += setOf("en", "en-rUS")` in `defaultConfig`
  (app is English-only). Real, safe, behavior-preserving.
  - normal: 52,113,970 -> 51,683,754 bytes (**-430 KB**).
  - 15 dex files (66.6 MB in openclaude) are already deflated to ~74% by R8.
- No change to bundled `.deb` / `.tgz` / `.zip` / hermes-rootfs (incompressible;
  kept bundled so first launch is fully offline/instant per product decision).

### Conclusion
Further APK size reduction requires **unbundling + download-on-first-launch**
(~120 MB/CLI flavor, ~272 MB hermes) — explicitly NOT done because the product
requires instant offline testing on install. Any future size work must start
from unbundling; compressing the already-compressed assets is a dead end.
