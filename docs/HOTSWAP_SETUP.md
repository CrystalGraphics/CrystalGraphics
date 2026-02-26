# CrystalGraphics Hotswap Redirect Preservation

**Purpose**: Preserve CrystalGraphics ASM redirect transforms across IntelliJ IDEA hotswap reloads  
**Scope**: Development workflow for `runClient` sessions  
**Requires**: HotSwapAgent + LWJGL 2.9.3 + LaunchClassLoader environment

---

## The Problem

CrystalGraphics uses an ASM transformer (`CrystalGraphicsTransformer`) to rewrite callsites
in other classes at load time. When IntelliJ hotswaps a class, the JVM redefines it from
the **original** bytecode -- the ASM redirects are lost. Without intervention, hotswapped
classes bypass the GL state mirror entirely, causing state desync.

The `CrystalGraphicsHotswapPlugin` hooks into HotSwapAgent's `REDEFINE` event and re-runs
the CrystalGraphics transformer on every hotswapped class, restoring the redirect layer.

---

## Required JVM Arguments

The `build.gradle.kts` `runClient` task already configures both agents:

```kotlin
tasks.named<JavaExec>("runClient") {
    val agent = findJarBySubstring("unimixins")
    jvmArgs("-javaagent:${agent.absolutePath}")

    val hotswapAgent = findJarBySubstring("hotswap-agent")
    jvmArgs("-javaagent:${hotswapAgent.absolutePath}")
}
```

Both `-javaagent` entries are required:

| Agent | Purpose |
|-------|---------|
| `unimixins` | Mixin bootstrap (standard GTNH moddev) |
| `hotswap-agent-core` | HotSwapAgent framework; discovers `CrystalGraphicsHotswapPlugin` via `hotswap-agent.properties` |

The plugin is registered through `src/main/resources/hotswap-agent.properties`:
```
pluginPackages=io.github.somehussar.crystalgraphics.hotswap
```

No additional IntelliJ run configuration changes are needed when launching via `runClient`.

---

## IntelliJ Workflow

1. Launch `runClient` from Gradle (or the generated IntelliJ run configuration).
2. Edit Java source files.
3. **Build > Recompile** the changed file(s), or use **Build > Build Project** (`Ctrl+F9`).
4. IntelliJ hotswaps the changed classes into the running JVM.
5. HotSwapAgent fires a `REDEFINE` event for each changed class.
6. `CrystalGraphicsHotswapPlugin.reloadClass()` intercepts the event and re-applies
   the CrystalGraphics transformer, restoring GL call redirects.

The cycle is: edit -> recompile -> automatic retransform. No restart needed.

---

## Configuration Flags

All flags are JVM system properties (`-D...`). Add them to the `runClient` JVM args
or IntelliJ run configuration as needed.

### `crystalgraphics.hotswap.disable`

Disables the hotswap plugin entirely. Hotswapped classes will **not** be retransformed.

```
-Dcrystalgraphics.hotswap.disable=true
```

Default: `false`

### `crystalgraphics.hotswap.verbose`

Enables debug logging for every retransform event. Logs which classes are processed
and which are skipped due to LaunchClassLoader exclusion prefixes.

```
-Dcrystalgraphics.hotswap.verbose=true
```

Default: `false`

Output appears in both the HotSwapAgent log (via `AgentLogger`) and the game log
(via Log4j `CrystalGraphics-Hotswap` logger).

### `crystalgraphics.hotswap.fullChain`

Controls which transformers run on hotswapped classes:

| Value | Behavior |
|-------|----------|
| `false` (default) | Only `CrystalGraphicsTransformer` runs. Fast, minimal side effects. |
| `true` | The **entire** LaunchClassLoader transformer chain runs. Required if other coremods also need to retransform, but slower and may cause issues with non-idempotent transformers. |

```
-Dcrystalgraphics.hotswap.fullChain=true
```

Use `fullChain` only when debugging interactions with other coremods. The default
single-transformer mode is correct for normal CrystalGraphics development.

---

## Limitations

### Standard Java 8 HotSwap

The stock JDK 8 HotSwap implementation supports **method body changes only**:

- Changing code inside an existing method: **works**
- Adding/removing methods: **fails** (UnsupportedOperationException)
- Adding/removing fields: **fails**
- Changing method signatures: **fails**
- Changing class hierarchy: **fails**

For method-body-only edits (the most common case during iterative development),
standard HotSwap is sufficient.

### DCEVM for Structural Changes

[DCEVM](https://dcevm.github.io/) is an alternative JVM that supports full structural
class redefinition -- adding methods, fields, changing hierarchy, etc. If your workflow
requires structural changes without restarting, install DCEVM as your JDK.

CrystalGraphics' hotswap plugin is compatible with DCEVM. No configuration changes
are needed; HotSwapAgent detects DCEVM automatically.

### Classes Not Retransformed

The plugin skips classes that match LaunchClassLoader's exclusion lists:

- `classLoaderExceptions` -- classes loaded by the parent classloader (e.g., `java.`, `javax.`)
- `transformerExceptions` -- classes exempt from transformation (e.g., `org.lwjgl.`)

This matches the original load-time behavior. If a class was not transformed at
startup, it will not be retransformed on hotswap.

---

## Troubleshooting

### Redirects Not Restored After Hotswap

1. Verify HotSwapAgent is loaded: look for `HOTSWAP AGENT` banner in console output at startup.
2. Check that `crystalgraphics.hotswap.disable` is not set to `true`.
3. Enable verbose logging (`-Dcrystalgraphics.hotswap.verbose=true`) and confirm
   `Retransforming <classname>` appears in the log.
4. Ensure the class is loaded by `LaunchClassLoader`, not the system classloader.
   Classes in exclusion lists are intentionally skipped.

### "Hot Swap Failed" Dialog in IntelliJ

This means the JVM rejected the redefinition -- typically a structural change
(new method/field). Options:

- Revert to a method-body-only change and retry.
- Install DCEVM for structural hotswap support.
- Restart `runClient`.

### CrystalGraphicsTransformer Not Found in Chain

If verbose logs show `CrystalGraphicsTransformer not found in chain, instantiating`,
the transformer is being created on demand. This is normal when the transformer was
registered after the classloader snapshot. The fallback instantiation produces
identical results.

### Full Chain Causes Errors

If `fullChain=true` causes crashes or corrupted classes, switch back to the default
(`fullChain=false`). Some transformers in the chain are not idempotent and produce
incorrect bytecode when run twice on the same class.

### Log Locations

| Logger | Where |
|--------|-------|
| `AgentLogger` (HotSwapAgent) | Console / stdout |
| `CrystalGraphics-Hotswap` (Log4j) | `logs/fml-client-latest.log` |
