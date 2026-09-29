# Hotswap on 1.7.10

**Purpose**: keep a hot-swapped class's transforms — its Mixins above all — in a running 1.7.10 dev client.
**Scope**: `runtime/mc/1710` dev runs, with HotSwapAgent attached.

HotSwapAgent hands the JVM a redefined class's **untransformed** bytes, so without help a reloaded class
loses every LaunchWrapper transform it had when it was first loaded — a mixin target silently stops
being mixed. `CrystalGraphicsHotswapPlugin` (`com.crystalgraphics.mc.v1710.hotswap`) hooks HotSwapAgent's
`REDEFINE` event and runs the **whole** LaunchWrapper transformer chain over the new bytes
(`TransformHelper`). HotSwapAgent finds the plugin through `src/main/resources/hotswap-agent.properties`.

## Attaching it

The Gradle `runClient` task does not add the agent. Attach it from the IDE run configuration:

```
-javaagent:<path to hotswap-agent-core.jar>
```

and hotswap the usual way — edit, **Build ▸ Recompile**, and the IDE redefines the changed classes.

## Flags

| Flag | Effect |
|---|---|
| `-Dcrystalgraphics.hotswap.disable=true` | redefined classes keep their raw bytes |
| `-Dcrystalgraphics.hotswap.verbose=true` | log each class transformed, and each skipped by LaunchClassLoader's exclusion lists |

`-Dcrystalgraphics.hotswap.fullChain` is read and ignored: the full chain is unconditional since the GL
redirect coremod it once narrowed to was deleted.

## Limitations

- **A class LaunchClassLoader never transformed is not transformed on redefine** — its
  `classLoaderExceptions` and `transformerExceptions` (`java.`, `org.lwjgl.` …) are skipped, as at load.
- **Stock HotSwap changes method bodies only.** Adding a method or field, or changing a signature or the
  hierarchy, needs a JDK with enhanced class redefinition (JetBrains Runtime with
  `-XX:+AllowEnhancedClassRedefinition`, or DCEVM); otherwise restart the client.
- **A transformer that is not idempotent can break on a second pass.** If a redefined class misbehaves
  and the first load did not, `-Dcrystalgraphics.hotswap.disable=true` tells the two apart.
