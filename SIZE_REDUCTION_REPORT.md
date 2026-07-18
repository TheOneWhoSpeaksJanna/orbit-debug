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
