# Third-party notices — CrystalGraphics

What this repository carries that somebody else wrote, where it lives, and under what terms. A row
exists for anything **redistributed** in a built jar; something read for reference and not shipped is
marked as such.

| What | Where | Licence | Notes |
|---|---|---|---|
| **JOML** | `crystalgraphics-joml-<version>.jar` — a **companion artefact**, not in the merged jar | **MIT** | © 2015–2024 Richard Greenlees. Verbatim, not forked, not relocated. Licence text below. **The API names `org.joml` and always will**: `com.crystalgraphics.api` takes JOML types in seven public signatures, so a consumer must be able to pass the `org.joml.Matrix4f` MC 1.19.3+ just handed them. The merged jar therefore carries **none** — a jar exporting `org/joml` fails Forge's module resolution against Minecraft's own JOML module — and the companion exists for the LWJGL2 targets, whose Minecraft ships no JOML and whose loader has no module system. **Install it on 1.7.10 and 1.12.2 only.** See CrystalGUI `plan/crystalgui/platform-single-jar.md` D2 |
| **Jackson** | `com.fasterxml.jackson`, relocated — 942 entries | Apache 2.0 | Pulled in by the glTF loader. Its own `META-INF/NOTICE` rides along in the jar |
| **javagl `obj` + `jgltf-model`** | `de.javagl`, relocated — 318 entries | **not yet confirmed here** | The OBJ and glTF loaders. The coordinates are `de.javagl:obj:0.4.0` and `de.javagl:jgltf-model:2.0.4`; upstream states MIT, but the artifacts are not in this machine's Gradle cache and the text has not been read, so it is recorded as unconfirmed rather than asserted. **Confirm before any public release** |
| **LWJGL 2 / LWJGL 3** | not redistributed | BSD-3-Clause | `compileOnly` everywhere. The game supplies it; a bundled copy would be a second `org.lwjgl` on a classpath that already has the one the loader booted with |
| **Mixin** | not redistributed | MIT | `compileOnly`. Every loader supplies it, and a second copy would be a second `MixinService` for the one already running |
| **FreeType · HarfBuzz · msdfgen** | `freetype-msdfgen-harfbuzz-bindings/` | FreeType (BSD-style) · MIT · MIT | JNI bindings and their natives, shipped in every loader jar. See that module for per-library provenance |
| Minecraft sources | `*/build/mc-src/` | Proprietary | Decompiled reference, generated locally. Not in the repository, not redistributed, not built |

## The notice that ships — a known gap

**CrystalGraphics' jar has no notice of its own.** What it carries is `META-INF/NOTICE` and
`META-INF/LICENSE` that arrived inside shaded dependencies — both Jackson's now that JOML has left the
jar, and until then they were a JOML licence beside a Jackson notice, one library's terms presented
against another's. Nothing chose either arrangement; whichever shaded jar won the name did.

The fix is CrystalGUI's shape: a `notices/crystalgraphics.md` written into the jar as
`META-INF/NOTICE.md` and asserted by `checkSingleJar`'s `requiredEntries`, so the notice travels with
the binary as MIT and Apache 2.0 both require. Not done — recorded so it is not rediscovered.

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
