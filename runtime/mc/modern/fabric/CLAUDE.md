# runtime/mc/modern/fabric — Agent Knowledge Base

## Target versions

**MC 1.14.4–26.3 / Fabric**, a node per `versions/<version>` (26.1.2 also claims 26.1 and 26.1.1). From
26.1 Minecraft is unobfuscated: those nodes apply `fabric-loom` rather than `fabric-loom-remap`, take no
mappings, and ship their jar as compiled (`fabricRunsIntermediary` in `ModernTree.kt`). Below 1.16 Fabric API has no
world-render event, so the 1.15.2 node hooks `LevelRenderer.renderLevel` and the 1.14.4 node
`GameRenderer.renderLevel` with a node mixin (`mixin/WorldPassHook`, gated by
`CrystalGraphicsFabricMixins`). From 1.21.9 no Fabric API event fires after clouds and weather, so the transparent
pass is `mixin/TransparentPassHook`: the tail of `LevelRenderer.renderLevel` on 1.21.10–26.1.2, and from 26.2 a point inside
the frame graph (`WorldBorderRenderer.render`'s return; 26.3 `executeOit`/`executeClassicTransparency`'s tail), since under
Vulkan the world's depth is gone once the graph has run;
1.16.5–1.21.8 take `WorldRenderEvents.LAST`. Pins and toolchains: `docs/BUILD.md` § *Nodes and toolchains*.

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
