# CrystalGraphics — Build Setup

## Environment

| Property | Value |
|---|---|
| Gradle | 9.5.1 |
| JDK (daemon) | 25 (pinned via `gradle-daemon-jvm.properties`) |
| Java toolchain (compile) | 17 for mc1201, 8-bytecode (Jabel) for mc1710 |
| Configuration cache | **disabled** — ModDevGradle does not support it |

Gradle 8.x is not a viable downgrade path: Gradle 8.12.1 predates JDK 25 and has no support for it.

---

## mc1710 (Minecraft 1.7.10 + Forge 10.13.4)

**Plugin**: `com.gtnewhorizons.gtnhconvention` 2.0.20 (wraps RetroFuturaGradle / RFG).

- All mc1710-specific version pins live in `mc1710/gradle.properties` (Minecraft, Forge, MCP channel/mappings, mixin/coremod config).
- Shared dep versions (`dep.*`) live in root `gradle.properties` and are referenced via `rootProject.properties["dep.*"]` in build scripts.
- The shadow JAR unpacks `:core`, `:platform`, and `:freetype-msdfgen-harfbuzz-bindings` into the final artifact via `zipTree` in `afterEvaluate`. These subprojects cannot use `shadowImplementation` because GTNH convention requires RFG obfuscation variant attributes they don't publish.
- `runClient` / `runServer` are provided by RFG out of the box. No extra configuration needed.

**Common issues**:
- *Build hangs at decompile step* — RFG decompiles MC; first run downloads and decompiles, takes 2–5 min. Do not kill early.
- *`idea.project is null` on subproject* — `gtnh.modules.ideIntegration = false` in `mc1710/gradle.properties` suppresses the IDEA module that only works on the root project.

---

## mc1201 (Minecraft 1.20.1 multiloader)

Three loader subprojects share a `mc1201/common` source set and a `mc1201/build-logic` composite build that provides the `cg-mc1201-loader` convention plugin (shared repos + Java toolchain).

### Fabric (`mc1201/fabric`)

**Plugin**: `net.fabricmc:fabric-loom` 1.16.2 (requires Gradle 9.4+).

- Architectury-loom was replaced because its Forge mode resolves `detachedConfiguration` inside the `jvmArguments` property getter — a Gradle 9 hard error (exclusive lock required outside task execution). No upstream fix was published.
- fabric-loom 1.16.2 moves the problematic resolution, making it Gradle 9 compatible.

**Known issue — IntelliJ `runClient` failure**:
IntelliJ injects a Kotlin coroutine debug init script (`ijKotlinCoroutineJvmDebugInit*.gradle`) that calls `task.getJvmArgs()` during configuration. In Gradle 9, this evaluates loom's lazy `jvmArguments` providers, which triggers `runtimeClasspath` resolution outside an exclusive lock. The `build.gradle.kts` contains a `configureEach` block that strips the offending JVM args when `idea.active=true`. If this filter stops working after a loom or IntelliJ upgrade, the fallback is: **IntelliJ → Settings → Build, Execution, Deployment → Debugger → uncheck "Kotlin - Attach coroutine agent"**. CLI `./gradlew runClient` is never affected.

### Forge (`mc1201/forge`)

**Plugin**: `net.neoforged.moddev.legacyforge` 2.0.141 (ModDevGradle).

- Explicitly designed for MinecraftForge 1.17–1.20.1. Gradle 9 + JDK 25 compatible out of the box.
- Plugin version is pinned via the `net.neoforged.moddev.repositories` settings plugin in `settings.gradle.kts`; all three `net.neoforged.moddev.*` plugins inherit that version automatically.
- Forge artifact ID format: `1.20.1-<forgeVersion>` (set in `legacyForge { version = "..." }`).

### NeoForge (`mc1201/neoforge`)

**Plugin**: `net.neoforged.moddev` 2.0.141. **Targets MC 1.20.4** (NeoForge 20.4.x) — NeoForge 20.1.x was never published to the NeoForge Maven. Version pins use `mc1204.*` keys in root `gradle.properties`. The directory name `mc1201/neoforge/` is kept for structural consistency.

- Requires two extra Maven repos declared at project level (`mojang-meta`, `minecraft libraries`) because the settings-level repos added by `net.neoforged.moddev.repositories` are overridden by the `cg-mc1201-loader` convention plugin's project-level repo block.

**Common mc1201 issues**:
- *Configuration cache errors* — `org.gradle.configuration-cache=false` in root `gradle.properties` is mandatory; ModDevGradle does not support it.
- *Heap OOM during Forge/NeoForge deobfuscation* — root `gradle.properties` sets `-Xmx4g -Xms1g`; do not reduce below 3 GB.
- *`runClient` works from CLI but fails in IntelliJ (Fabric only)* — see the IntelliJ init script issue above.
