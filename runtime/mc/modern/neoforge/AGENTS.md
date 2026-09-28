# runtime/mc/modern/neoforge — Agent Knowledge Base

## Target versions

**MC 1.20.2–1.21.11 / NeoForge**, a node per `versions/<version>`: 1.20.2, 1.20.3, 1.20.4, 1.20.6 (also
1.20.5), 1.21.1 (also 1.21), 1.21.3 (also 1.21.2), 1.21.4, 1.21.5, 1.21.6, 1.21.8 (also 1.21.7), 1.21.10
(also 1.21.9) and 1.21.11. NeoForge published nothing for 1.20.1. NeoForge 20.2/20.3 are built from parts through NeoForm and have no dev run; from 1.20.4 it
is ModDevGradle. Pins and toolchains: `docs/BUILD.md` § *Nodes and toolchains*.

## The loader is registration only

`NeoForgeBootstrap` is the one `@Mod` class for every NeoForge node, and hands off to this node's
`CrystalGraphicsNeoForge` (a `VariantEntry`) by the running version. Its `Events` inner class registers
the render stages and the shutdown signal on `NeoForge.EVENT_BUS`, and the mod-bus reload listener. What the engine then does is the common branch's
`LifecycleModern`.

## Minecraft sources

```bash
./gradlew :runtime:mc:modern:neoforge:<version>:extractMcSources   # into versions/<version>/build/mc-src/{java,resources}
```

Gitignored. The task makes that node real, so the first run sets up its toolchain (several minutes).
