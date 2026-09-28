# CrystalGraphics — the build, and adding a Minecraft version

The one document for how this build is laid out, how to run it, and how to add a Minecraft version.
The mechanism behind it is [`singlejar-logic/README.md`](../singlejar-logic/README.md) (the merge, the
tree, 23 traps) and [`singlejar-logic/STUBS.md`](../singlejar-logic/STUBS.md) (stub mode); read those
before changing the build logic itself. Code that has to run on every version: CrystalGUI's
`docs/CGUI_CROSS_VERSION.md`.

**CrystalGraphics is the parent.** CrystalGUI (and any mod on CrystalGraphics) compiles each Minecraft
node against the CrystalGraphics node of the same version, so **a version is always added here first**.

---

## What it produces

| Artifact | Task | Installs on |
|---|---|---|
| `build/libs/crystalgraphics-<v>.jar` | `singleJar` (+ `checkSingleJar`) | every supported loader and version: Forge 1.7.10, 1.8.8–1.21.11 · NeoForge 1.20.2–1.21.11 · Fabric 1.14.4–1.21.11 (exceptions in CrystalGUI's `AGENTS.md`) |
| `build/libs/crystalgraphics-joml-<v>.jar` | built with it | **only** instances below Minecraft 1.19.3 (Minecraft ships JOML from 1.19.3; a second copy there is a split package) |

One jar serves every loader because a class file is inert until something defines it; the whole jar is
downgraded to Java 8 because FML 1.7.10 reads every class in it with ASM 5. Details: the README.

## Requirements

- **JDK 25** — the one compiler (`dep.jdk.compiler`) and the Gradle daemon (`gradle/gradle-daemon-jvm.properties`).
- **JDK 21, 17 and 8** installed for dev runs; Gradle's toolchain resolver finds them.
- Gradle 9.5.1 (wrapper). **Configuration cache stays off** — ModDevGradle does not support it.
- `-Xmx4g` (set in `gradle.properties`); decompiling a Minecraft needs it.

## Layout

| Module | What | Java |
|---|---|---|
| `platform/` | the SPI: `CgPlatform`, `CgPlatformService` (closed bundle), `CgService` (open slots) | 25, + Java 8 copy |
| `core/` | all rendering; names no Minecraft, loader or LWJGL type | 25, + Java 8 copy |
| `runtime/lwjgl/2`, `runtime/lwjgl/3` | tier 1: GL backend, context, input, cursor per LWJGL; no Minecraft | 25, + Java 8 copy |
| `freetype-msdfgen-harfbuzz-bindings/` | JNI text shaping | 8 |
| `runtime/mc/shared/` | variant selector, `LoaderProbe`, `CrashVariant`, mixin-plugin base; merged once, never relocated | 8 |
| `runtime/mc/forge-bootstrap/` + `forge-stubs/` | the one `@Mod` class for every Forge 1.8+ | 8 |
| `runtime/mc/1710/` | Forge 1.7.10, RetroFuturaGradle (GTNH convention) | 25 → 8 |
| `runtime/mc/modern/` | Stonecutter tree: branches `common`, `forge`, `neoforge`, `fabric`; a node per version | node's own |
| `runtime/mc/legacy/` | Stonecutter tree: branch `forge`, nodes `1.8.9`, `1.10.2`, `1.12.2` | 8 |
| `singlejar-logic/` | shared build logic (merge, tree, stubs) + `stubs.zip`; used by CrystalGUI too | — |
| `runtime/mc/modern/build-logic/` | this repo's convention plugins (`cg-java`, `cg-modern-loader`, `cg-legacy-loader`, `cg-single-jar`, `cg-descriptors`) | — |

**Java levels.** The abstract modules (`platform`, `core`, `runtime/lwjgl/*`) are Java 25 and publish a
Java 8 copy that every consumer below 25 resolves automatically (`cgbuildlogic.abstractModule`). A node
emits its Minecraft's Java (`nodeJava`: 17, or 21 from 1.20.5 via `java.version = 21`). The shipped jar is
downgraded to 8 in one pass. **javac does not check the API**: a Java 9+ method is compiled happily and
fails on a Java 8 instance unless jvmdg stubs it — Forge ≤1.16, legacy Forge and 1.7.10 run Java 8.

**Which loaders a build includes.** From this checkout or from inside CrystalGUI: all of them. Included
by any other build (a consumer mod): only `common` and `forge` at 1.20.1 (`loadersWanted` in
`settings.gradle.kts`).

## Nodes and toolchains

A node is `:runtime:mc:<tree>:<branch>:<version>`. `settings.gradle.kts` names the versions the build
ships, and `singlejar-logic`'s settings plugin turns them into nodes:

```kotlin
singlejar {
    targets {
        forge("1.7.10".."1.21.11")      // the 1.7.10 host, the legacy tree, the modern forge branch
        neoforge("1.20.2".."1.21.11")
        fabric("1.14.4".."1.21.11")
    }
}
```

A range takes every node whose claimed range touches it; `common` follows the loaders. Every node's pins
come from the **pin catalog**, `singlejar-logic/src/main/resources/cgbuildlogic/catalog/<tree>/<branch>/<version>.properties`,
shared by both repos; a node's own `versions/<version>/gradle.properties` holds only what differs per
project (`variant.mixinPlugin`). **On a node, `project.name` is the version.** The toolchain is chosen by
the pins:

| Nodes | Pins that select it | Toolchain | Dev run |
|---|---|---|---|
| common ≥1.20.2 · Forge ≥1.20.2 · NeoForge 1.20.2–1.20.3 | `neoform.version` | ModDevGradle NeoForm (+ loader jars compileOnly) | **no** — prodSmoke only |
| common · Forge 1.17.1–1.20.1 | `forge.version` only | ModDevGradle `legacyForge` | Forge: yes |
| NeoForge ≥1.20.4 | `neoforge.version` | ModDevGradle | yes |
| common <1.17 | `minecraft.loom = true` (1.13.2: `minecraft.unimined = true`, applied by `unimined-vanilla.gradle.kts`) | Loom / Unimined vanilla | — |
| Forge 1.13.2–1.16.5 | `minecraft.unimined = true`, `mcp.version` | Unimined | yes, **on Java 8** (CrystalGUI's `uniminedDevRun`) |
| Fabric (all) | `fabric.loader`, `fabric.api` | Loom | yes |
| legacy Forge | `minecraft.unimined`, `mcp.version`, `mcp.mappings` | Unimined FG2 | **no** — prodSmoke, `server_smoke.py` |
| 1.7.10 | `runtime/mc/1710/gradle.properties` | RFG | yes |

**Stub mode is the default**: a node compiles against its slice of `singlejar-logic/stubs.zip` and sets
up no toolchain. A node becomes real when you request one of its run tasks (`runClient`, `runServer`,
`serverSmoke`, `checkStubEquivalence`, …), when it is the active node during an IDE sync, when named in
`-PcgRealNodes=forge:1.20.4,1.21.1`, or when `stubs.zip` has no entry for it. `-PcgStubs=false` makes every
node real (hours and tens of GB on a clean machine).

## Commands

```bash
./gradlew checkAllTargets                        # every node, every source set -- before every commit
./gradlew singleJar checkSingleJar               # the shipped jar
./gradlew :core:test --tests "<Class>"           # NEVER unscoped: the full suite hangs
./gradlew :runtime:mc:modern:common:1.20.1:test  # the Blaze3D mirror override check (F5)
./gradlew :runtime:mc:modern:<branch>:<version>:checkStubEquivalence   # real vs stub, byte for byte
./gradlew -p singlejar-logic generateStubDatabase                      # regenerate stubs.zip
python singlejar-logic/mcapi.py <Class> [member]                       # any node's API, as version runs
```

`serverSmoke`, `prodSmoke` and useful dev runs are driven from CrystalGUI: this mod alone draws nothing.

## Publishing

`./gradlew publishToMavenLocal`, at the root version (`modVersion`). The mechanism is the README's
§ *Publishing*.

| Coordinate | What | Consumer |
|---|---|---|
| `com.crystalgraphics:core` | the engine — jar, `java8` copy, sources, javadoc | compiles against it |
| `com.crystalgraphics:platform` | the SPI | comes with `core` |
| `com.crystalgraphics:freetype-msdfgen-harfbuzz-bindings` | JNI bindings and natives (MIT) | comes with `core` |
| `com.crystalgraphics:crystalgraphics` | the shipped jar | runs it in a dev client |
| `com.crystalgraphics:crystalgraphics-joml` | JOML as a mod (MIT) | runs it below Minecraft 1.19.3 |

**The API is checked on every build.** Each library's public declarations are committed as
`api/<artifact>.api`; `apiCheck`, part of `check`, fails when one is gone or changed and the major
version has not moved. The file is the API as last RELEASED, so additions never fail. At a release, or
after a deliberate major break, `./gradlew apiDump` rewrites it — commit the diff with the change.

`core`'s API names the real `org.joml` (1.10.5 in its metadata). A library's `consumerApi` is the
whole of what a consumer gets; a type in the public API from anything else is a compile error for them.

---

## Adding a Minecraft version

A version is a **node**: a catalog entry here, and each repo's sources made to build it.

**0. Survey first.** Diff the new version's API against its neighbour before touching the build (javap
over the new jars, or its `build/mc-src` once real). Every break found up front is one `prodSmoke` cycle
saved; fix every family of break in one pass.

**1. `targets {}` in both repos' `settings.gradle.kts`** — a range that reaches the version; widening the
upper bound is usually the whole edit. The `common` node follows.

**2. The catalog entry**, `singlejar-logic/src/main/resources/cgbuildlogic/catalog/modern/<branch>/<version>.properties`,
**and `modern/common/<version>.properties`** if absent (a loader node compiles against the common node of
its own version, never a neighbour's). Copy the nearest node's and change:

| Key | Meaning |
|---|---|
| `mc.version` | the Minecraft version |
| `forge.version` / `neoforge.version` / `fabric.loader` + `fabric.api` | the loader pins |
| `neoform.version` | builds from parts (see table) — Forge ≥1.20.2, NeoForge 20.2/20.3, common ≥1.20.2 |
| `mcp.version` | SRG table, for Forge nodes that run SRG names (<1.20.6) through a non-MDG path |
| `minecraft.unimined` / `minecraft.loom` | below 1.17 |
| `parchment.mc`, `parchment.version` | parameter names (1.16.5+; optional) |
| `java.version = 21` | from 1.20.5 on |
| `asm` | NeoForge's ASM version (a dev run must not upgrade the loader's ASM) |
| `variant.minecraft` | the range this node claims, e.g. `[1.21.11,1.21.12)` — **narrow the neighbour**; overlapping ranges fail configuration |
| `variant.packFormat` | resource pack format of that version |
| `variant.mixinPlugin` | **not here**: per project, in that repo's `versions/<version>/gradle.properties`, only if its node ships mixins (no loader event for a hook) |

**3. `//? if` directives** in the branch's `src/` where the API differs. `checkAllTargets` finds every one.
Build the new node real until it compiles: `-PcgRealNodes=<branch>:<version>`.

**4. Regenerate `stubs.zip`** and commit it with the node:

From CrystalGUI's root, once both repos have the node (the database holds both repos' nodes):

```bash
./gradlew :runtime:mc:modern:<branch>:<version>:listStubInputs                    # CrystalGUI's node
./gradlew -p CrystalGraphics :runtime:mc:modern:<branch>:<version>:listStubInputs # this repo's
./gradlew -p CrystalGraphics/singlejar-logic generateStubDatabase                 # ~1 minute
./gradlew :runtime:mc:modern:<branch>:<version>:checkStubEquivalence              # and -p CrystalGraphics
```

List a `common` node too when it is new. `unknown` in a Fabric listing means Loom had not resolved its
manifest: rerun that node's `remapThinJar --rerun-tasks` and list again.

**Not edited**: thin-jar lists, relocation counts, descriptors (including `neoforge.mods.toml`), variant
tables, `requiredEntries`, node directories (created when missing) — all follow the tree.

**Then CrystalGUI** (its `docs/CGUI_BUILD.md` § *Adding a Minecraft version*): the same node, a Prism
instance, `serverSmoke` where the node has a dev run, one `prodSmoke` of the new target, and read the
capture.

### A version that does not fit an existing toolchain

If no pin combination above builds it — a new mappings scheme, a loader whose Gradle plugin does not run
on Gradle 9, a new Java floor — the change is in `singlejar-logic` (`useModernMinecraft`, `ModernForge`,
`registerThinRename`, `StubMode`), not in a node. Spike it on one node, keep `checkStubEquivalence`
identical for every existing node, and write the new row into the table above and a bite into the
README. Legacy plateaus are `LegacyTree.kt`; check a widened legacy claim with
`runtime/mc/legacy/claims.py`.

---

## Caveats that bite

- **Switching the active node rewrites `src/` in place.** The controller is
  `runtime/mc/modern/stonecutter.gradle.kts` (`stonecutter active "1.20.1"`). Switch back before
  committing. Sources are written at the active version's spelling; other versions' branches sit inside
  `/* */` in the file.
- **Directives go in the branch `src/`**, never in `versions/<v>/build/generated/stonecutter/`.
- **A rename across many files is a replacement, not a directive** — `stonecutter.gradle.kts`
  `replacements.string(...)`. Its target must never occur in the sources, since it runs in reverse on
  every older node.
- **A bootstrapper names no Minecraft class**; one copy serves every node of its loader.
- **Strings are not remapped.** Reflection by member name works on NeoForge (Mojang names) and fails on
  Forge <1.20.6 (SRG) and Fabric (intermediary). Call Minecraft in compiled code.
- **No JOML below 1.19.3**; the companion jar provides it in production and a library in dev runs.
- **A `CgPlatformService` method is abstract on purpose**: five classes implement the bundle
  (`PlatformService1710`, `PlatformServiceLegacy`, `PlatformServiceModern`, the harness's, core's
  `TestPlatformService`). Optional services are `CgService` slots instead.
- **`serverSmoke` is not a pass unless it printed `RESULT: pass`**; an absent report is a failure.
- **Every stub was byte-identical when last checked.** A difference after a toolchain upgrade is a real
  finding, not noise — regenerate and investigate before committing.
- The full list, with the incident behind each: README § *What bites*.
