# Atlas Dump Harness — Multi-Page, Parity Prewarm, and Overflow Validation

## Overview

The atlas dump harness (`--mode=atlas-dump`) captures glyph atlas textures to PNG files
and writes manifest files with per-page utilization data. It supports:

- **Multi-page dumping**: Enumerate and dump every atlas page, not just the first
- **Deterministic parity prewarm**: Pre-generate all MSDF glyphs before capture for dense packing
- **Bitmap prewarm**: Same concept for bitmap glyphs
- **Forced pagination**: Override atlas page size to trigger overflow onto multiple pages
- **Rich manifests**: Per-page glyph count, packed area, utilization percentage, and aggregates

## CLI Knobs

### Parity / Overflow Flags

| Flag | Values | Default | Description |
|------|--------|---------|-------------|
| `--parity-prewarm` | `true`/`false` | `false` | Deterministic MSDF prewarm for parity testing |
| `--prewarm-bitmap` | `true`/`false` | `false` | Deterministic bitmap prewarm |
| `--dump-all-pages` | `true`/`false` | `false` | Dump every atlas page (not just the first) |
| `--atlas-page-size` | integer | auto | Override per-page atlas texture dimension |

### Existing Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--atlas-type` | `both` | `bitmap`, `msdf`, or `both` |
| `--msdf-px-size` | `32` | MSDF font size in px (min: 32) |
| `--bitmap-px-size` | `24` | Bitmap font size in px |
| `--font-size-px` | varies | Overrides both bitmap and MSDF |
| `--text` | ASCII printable | Test string |
| `--font-path` | system font | Font file path |

## Validation Scenarios

### 1. 128px MSDF Parity Prewarm

Exercise the 128px MSDF parity mode from the atlas overhaul plan:

```bash
./gradlew.bat :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=msdf --msdf-px-size=128 --parity-prewarm=true --dump-all-pages=true"
```

Expected output:
```
gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px-page-0.png
gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px-manifest.txt
```

Compare against the reference atlas:
- `gl-debug-harness/resources/MSDF-Gen-Atlas atlas.png` (msdf-atlas-gen reference)
- `gl-debug-harness/harness-output/atlas_pre_overhaul/msdf-atlas-dump-128px.png` (pre-overhaul baseline)

### 2. Forced Multi-Page MSDF Overflow

Force pagination by using a small atlas page size:

```bash
./gradlew.bat :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=msdf --msdf-px-size=128 --atlas-page-size=512 --parity-prewarm=true --dump-all-pages=true"
```

Expected output:
```
gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px-page-0.png
gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px-page-1.png
gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px-manifest.txt
```

### 3. Forced Multi-Page Bitmap Overflow

```bash
./gradlew.bat :gl-debug-harness:runHarness --args="--mode=atlas-dump --atlas-type=bitmap --bitmap-px-size=24 --atlas-page-size=256 --prewarm-bitmap=true --dump-all-pages=true"
```

Expected output:
```
gl-debug-harness/harness-output/atlas/bitmap-atlas-dump-24px-page-0.png
gl-debug-harness/harness-output/atlas/bitmap-atlas-dump-24px-manifest.txt
```

### 4. Default Smoke Test (Legacy Path)

```bash
./gradlew.bat :gl-debug-harness:runHarness --args="--mode=atlas-dump"
```

Uses the legacy single-page dump path with backward-compatible filenames.

## Output Filename Convention

### Multi-page mode (`--dump-all-pages=true`)

```
<type>-atlas-dump-<size>px-page-<N>.png
<type>-atlas-dump-<size>px-manifest.txt
```

Examples:
- `msdf-atlas-dump-128px-page-0.png`
- `msdf-atlas-dump-128px-page-1.png`
- `msdf-atlas-dump-128px-manifest.txt`
- `bitmap-atlas-dump-24px-page-0.png`
- `bitmap-atlas-dump-24px-manifest.txt`

### Single-page mode (default)

```
<type>-atlas-dump-<size>px.png
```

Examples:
- `msdf-atlas-dump-128px.png`
- `bitmap-atlas-dump-24px.png`

## Manifest Format

### Multi-page manifest

```
=== Atlas Multi-Page Manifest ===
Base: msdf-atlas-dump-128px
Page Count: 2

--- Page 0 ---
  Texture ID: 3
  Dimensions: 512x512
  Type: MSDF
  Glyph Count: 12
  Packed Area: 147456 px
  Utilization: 56.3%

--- Page 1 ---
  Texture ID: 4
  Dimensions: 512x512
  Type: MSDF
  Glyph Count: 8
  Packed Area: 98304 px
  Utilization: 37.5%

--- Totals ---
  Total Glyphs: 20
  Total Packed Area: 245760 px
  Total Page Area: 524288 px
  Overall Utilization: 46.9%

=== End Atlas Multi-Page Manifest ===
```

## Prewarm Convergence Logic

The prewarm loop renders the full text repeatedly, ticking the registry between
frames to reset the per-frame MSDF generation budget (`CgMsdfGenerator.MAX_PER_FRAME = 4`).
It terminates when two consecutive frames produce no new atlas allocations.

A safety cap prevents infinite loops: `maxFrames = (uniqueChars / MAX_PER_FRAME) + 20`.

## Architecture Notes

### Key Classes

| Class | Role |
|-------|------|
| `AtlasDumpConfig` | CLI/system-property parsing for all dump knobs |
| `AtlasDumpScene` | Orchestrates rendering, prewarm, and capture |
| `AtlasDumper` | PNG capture + manifest writing (multi-page aware) |
| `CgGlyphAtlas` | Legacy single-page atlas: `getSlotCount()`, `getPackedArea()`, `getUtilization()` |
| `CgGlyphAtlasPage` | Paged atlas page: `getSlotCount()`, `getPackedArea()`, `getUtilization()`, `getGlyphKeys()` |
| `CgFontRegistry` | Provides `findAllPopulatedPagedBitmapPages()`, `findAllPopulatedPagedMsdfPages()`, plus legacy `findAllPopulatedBitmapAtlases()`, `findAllPopulatedMsdfAtlases()` |

### Paged Atlas Integration

The harness integrates with both the legacy single-page atlas model
(`CgGlyphAtlas`) and the paged atlas system (`CgPagedGlyphAtlas` /
`CgGlyphAtlasPage`). The live renderer now allocates through the paged
path by default (`CgFontRegistry.ensureGlyphPaged`).

The dump and prewarm logic prefers the paged path:

1. `CgFontRegistry.findAllPopulatedPagedBitmapPages()` / `findAllPopulatedPagedMsdfPages()` — checked first for paged atlas pages
2. `AtlasDumper.dumpAllPagedPages()` — dumps `CgGlyphAtlasPage` instances with manifest support
3. Falls back to legacy `findAllPopulatedBitmapAtlases()` / `findAllPopulatedMsdfAtlases()` when no paged pages are found
4. Prewarm convergence logic (`countTotalAtlasSlots`) counts slots from both paged and legacy paths

### Test Coverage

- `AtlasDumpConfigTest` — All CLI knobs, boolean parsing, combined scenarios
- `AtlasDumperManifestTest` — Manifest generation, utilization math, filename convention
- `AtlasDumpSceneConfigTest` — Config parsing for all plan verification scenarios
