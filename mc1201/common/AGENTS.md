# mc1201/common — Agent Knowledge Base

Shared MC 1.20.x platform implementation. Compiles against MC 1.20.1 + MinecraftForge 47.2.0
via `legacyForge` in `cg-mc1201-common.gradle.kts`. The compiled JAR is consumed by all three
loader subprojects (`forge`, `neoforge`, `fabric`) via the `commonOutput` configuration.

## Build

```bash
./gradlew :mc1201:common:compileJava   # compiles shared sources only
```

No loader-specific types (Forge/NeoForge/Fabric APIs) appear in this module.

## Package Guide

| Package | AGENTS.md | What it contains |
|---|---|---|
| `com.crystalgraphics.mc.platform` | [platform/AGENTS.md](src/main/java/com/crystalgraphics/mc/platform/AGENTS.md) | GL backend, services, lifecycle bridge, `PlatformService1201` |
| `com.crystalgraphics.mc.mixin` | ↑ same file | `MixinGameRenderer`, `MixinMinecraftShutdown` |

## Key Design Points

- **No GL calls in constructors** — all GL work deferred to first `CgGraphicsLifecycle.onOpaquePass` call (lazy init via `onRenderFrame`)
- **Mixin AP**: provided by `legacyForge`; do NOT add a second `annotationProcessor` for Mixin in this module — it causes duplicate-AP SRG mapping errors
- **`legacyForge` not `neoForge`**: NeoForm 1.20.1 was never published; `legacyForge{version="1.20.1-47.2.0"}` is the only ModDevGradle path
