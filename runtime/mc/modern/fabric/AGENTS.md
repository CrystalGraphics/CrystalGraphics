# runtime/mc/modern/fabric — Agent Knowledge Base

## Target versions

**MC 1.14.4–1.21.11 / Fabric**, a node per `versions/<version>`. Below 1.16 Fabric API has no
world-render event, so the 1.15.2 node hooks `LevelRenderer.renderLevel` and the 1.14.4 node
`GameRenderer.renderLevel` with a node mixin (`mixin/WorldPassHook`, gated by
`CrystalGraphicsFabricMixins`). Pins and toolchains: `docs/BUILD.md` § *Nodes and toolchains*.

## The loader is registration only

`fabric.mod.json` names one class for both entry points, `FabricBootstrap`: Fabric constructs every
entry point its descriptor names, so naming the variants directly would construct one compiled against a
Minecraft that is not running. It hands off by the running version to this node's two entries:

- `CrystalGraphicsFabricCommon` (`main`) registers the platform bundle — a dedicated server runs no
  `client` entry point, so registering there would leave `CgPlatform` unset for the whole server.
- `CrystalGraphicsFabric` (`client`) carries the `Events` inner class, which is all render hooks.

What the engine then does is the common branch's `LifecycleModern`.

## The dev run loads our classes from the mod jar

Knot does not load `com.crystalgraphics.*` from the JVM classpath, only from the mod jar it finds through
`fabric.mod.json`. So `tasks.jar` bundles every module the dev client needs — the Java 8 copies of
`platform`, `core` and `runtime/lwjgl/3` (`downgradedJar`), the common node, the bindings and
`runtime/mc/shared` — and Loom's `remapJar` of it is the dev mod.

**Never `loom.mods { sourceSet(crossProject) }`**: Loom tries to apply `fabric-loom-companion` to that
project, and a project without Loom fails with *"Plugin with id 'net.fabricmc.fabric-loom-companion' not
found"*.

## Minecraft sources

```bash
./gradlew :runtime:mc:modern:fabric:<version>:extractMcSources   # into versions/<version>/build/mc-src/{java,resources}
```

Gitignored; Loom decompiles with Vineflower (`genSourcesWithVineflower`). The task makes that node real,
so the first run sets up its toolchain (several minutes).
