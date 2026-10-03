# Third-party notices — CrystalGraphics

CrystalGraphics itself is licensed **LGPL-3.0-or-later**: [`COPYING.LESSER`](COPYING.LESSER), with the GPL-3.0 it builds on in [`COPYING`](COPYING). Both ship in every jar under `META-INF/`.

What this repository carries that somebody else wrote, where it lives, and under what terms. A row
exists for anything **redistributed** in a built jar; something read for reference and not shipped is
marked as such.

| What | Where | Licence | Notes |
|---|---|---|---|
| **JOML** | `crystalgraphics-joml-<version>.jar` — a **companion artefact**, not in the merged jar | **MIT** | © 2015–2024 Richard Greenlees. Verbatim, not forked, not relocated. Licence text below. **The API names `org.joml` and always will**: `com.crystalgraphics.api` takes JOML types in seven public signatures, so a consumer must be able to pass the `org.joml.Matrix4f` MC 1.19.3+ just handed them. The merged jar therefore carries **none** — a jar exporting `org/joml` fails Forge's module resolution against Minecraft's own JOML module — and the companion exists for the LWJGL2 targets, whose Minecraft ships no JOML and whose loader has no module system. **Install it on 1.7.10 and 1.12.2 only.** See CrystalGUI `plan/crystalgui/platform-single-jar.md` D2 |
| **Jackson** | `com.fasterxml.jackson`, relocated — 942 entries | Apache 2.0 | Pulled in by the glTF loader. Its own `META-INF/NOTICE` rides along in the jar |
| **javagl `obj` + `jgltf-model`** | `de.javagl`, relocated — 319 entries | **MIT** | © 2008–2015 Marco Hutter (Obj), © 2016 Marco Hutter (JglTF, with `jgltf-impl-v1`/`-v2`). The OBJ and glTF loaders, `de.javagl:obj:0.4.0` and `de.javagl:jgltf-model:2.0.4`. **Confirmed 2026-09-28**: Obj's POM declares MIT; JglTF's inherits "MIT X11" from `jgltf-parent` 2.0.4; both LICENSE files read at the tags |
| **LWJGL 2 / LWJGL 3** | not redistributed | BSD-3-Clause | `compileOnly` everywhere. The game supplies it; a bundled copy would be a second `org.lwjgl` on a classpath that already has the one the loader booted with |
| **Mixin** | not redistributed | MIT | `compileOnly`. Every loader supplies it, and a second copy would be a second `MixinService` for the one already running |
| **FreeType · HarfBuzz · msdfgen** | `freetype-msdfgen-harfbuzz-bindings/`, compiled into `natives/` | FreeType License (FTL) · "Old MIT" · MIT | FreeType 2.13.2, HarfBuzz 8.3.0, msdfgen 1.13 — the pins are in that module's `gradle.properties`. Credit and texts: `notices/crystalgraphics.md` |
| **Chromium (Blink) font fallback** | `core/src/main/java/com/crystalgraphics/text/font/ScriptFallbacks.java` | **BSD-3-Clause** | © 2006–2012 Google Inc. **Ported source, modified**: the Windows script-to-font table and the rules choosing a row, from `platform/fonts/win/font_fallback_win.cc`, `platform/text/character.cc` and `platform/text/layout_locale.cc`. The licence text is the file's header comment, which ships in the jar with the sources (`assets/crystalgraphics/sources/`). The macOS and Linux tables are this project's |
| **Skia** mask blur | `core/src/main/java/com/crystalgraphics/text/shadow/{CgMaskBlurFilter,CgGaussFilter}.java` | **BSD-3-Clause** | © 2017 Google LLC. **Ported source, modified**: `src/core/SkMaskBlurFilter.cpp` and `src/core/SkGaussFilter.cpp`, the blur `SkScalerContext` applies to a mask-filtered glyph. The SIMD loops are scalar here over the same fixed-point values; only the A8 mask format is ported |
| **Skia Graphite** analytic rect blur | `core/src/main/resources/assets/crystalgraphics/shaders/lib/rect_blur.glsl` | **BSD-3-Clause** | © 2023–2024 Google LLC. **Ported source, modified**: `$rect_blur_coverage_fn` (`src/sksl/sksl_graphite_frag.sksl`), set up as `AnalyticBlurMask::MakeRect` does, with `CreateIntegralTable` (`src/gpu/BlurUtils.cpp`) evaluated as the `erf` it tabulates rather than sampled from a texture |
| **AMD FidelityFX Parallel Sort** | `core/src/main/resources/assets/crystalgraphics/shaders/env/compute/ops/sort.compute` (the `Sort*` kernels) | **MIT** | © 2020 Advanced Micro Devices, Inc. **Ported source, modified**: `FFX_ParallelSort.h`'s count, reduce, scan, scan-add and scatter passes, in GLSL over the engine's subgroup operations; it places every key where the every-tier sort does. Licence text in `notices/crystalgraphics.md` |
| **Robert Penner's easing equations** | `core/src/main/java/com/crystalgraphics/easing/CgEasings.java` | **BSD-3-Clause** | © 2001 Robert Penner. **Ported, in the forms easings.net gives them**: the sine, quad, cubic, quart, quint, expo, circ, back, elastic and bounce families. Licence text in `notices/crystalgraphics.md` |
| Test fonts | `core/src/test/resources/fonts/` | SIL OFL 1.1 | IBM Plex Sans, IBM Plex Sans Arabic, M PLUS 1p, M PLUS Rounded 1c, Noto Sans Arabic, `test-font.ttf`. **Not redistributed** — they left `src/main` on 2026-09-11, taking 11.6 MB out of the jar. Text a consumer's fonts cannot draw falls back to installed fonts through `CgSystemFonts` |
| Minecraft sources | `*/build/mc-src/` | Proprietary | Decompiled reference, generated locally. Not in the repository, not redistributed, not built |

## The notice that ships

[`notices/crystalgraphics.md`](notices/crystalgraphics.md) is written into the jar as
`META-INF/NOTICE.md` and required by `checkSingleJar`, so every notice MIT, BSD, Apache 2.0 and the FTL
require travels with the binary, with the texts in full. It also says that the `META-INF/LICENSE` and
`META-INF/NOTICE` beside it are Jackson's, which arrive by shading. **A dependency added to the jar gets
a row there in the same commit.**

## Licence texts

### JOML — MIT

Held here rather than in a `LICENSE_joml` of its own, so there is one place to look. It is owed
because `crystalgraphics-joml-<version>.jar` ships JOML's classes verbatim — the merged jar no longer
does, but the companion is still a distribution.

```
The MIT License

Copyright (c) 2015-2024 Richard Greenlees

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
