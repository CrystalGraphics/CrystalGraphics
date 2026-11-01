# Precaching: nothing compiles in front of the player

**Every shader variant a frame draws, and every kernel it dispatches, must be built before that frame.** A program
built at its first draw costs 7–110 ms on GL. A Vulkan pipeline built at its draw stalls the frame too. The engine
never compiles in the middle of a frame: a draw whose programs are not built is held until they are, so it appears
late, and a precache is what makes it appear on time. This is Unreal's PSO precaching, and `CgPipelinePrecache` is its
one entry point.

Read this before drawing with a shader of your own, adding a shader to the engine, or adding a draw path.

## What is precached for you

| You use | What happens | You do |
|---|---|---|
| A VFX look a cue plays (`VFX.cue(...)`, `VFX.register(cue, look, ...)`) | precached at the graphics start, inside the loading screen, and rehearsed | nothing |
| A VFX look in `CgVfxRegistry` | precached at the graphics start, and rehearsed | nothing (`.precache(false)` opts out one played rarely) |
| A VFX look played with no cue (a hit spark, a trail, a projectile) | precached if you name it once | `VFX.precache(LOOK)` at your mod's init, any thread |
| A VFX system asset, from a kit or a mounted pack | precached and rehearsed before it is READY | nothing |
| A skinned mesh's material (`CgAnimInstance.material`) | requested as it is set | nothing |
| A `CgPostMaterial` | built when first active, drawn once built (a few frames late) | nothing; add it at load if it must show on its first frame |
| A CrystalGUI widget's paint material | precached at paint, drawn once built | nothing |
| The engine's own passes: the world renderer's, the post stack, bloom, text, the depth pyramid, the VFX kernels | registered by the engine, built at the graphics start | nothing |

**Everything else is yours to precache.**

## What you must precache

| You draw | Precache it with |
|---|---|
| A material of your own, drawn through `CgWorldRenderer` or at a render stage | `pipelines.add(material, half)`: every pass the world draws it with |
| A material on a skinned mesh you draw yourself | `pipelines.add(material, half, true)` |
| A shader whose keywords your renderer switches at runtime | declare them in the shader as `#pragma cg_permutation` ([`SHADERS.md`](SHADERS.md) § *Keywords a renderer switches*), then `pipelines.add(shader)`: every combination, none typed by hand |
| A full-screen pass of your own renderer | `pipelines.addPass(shader)`, its permutation domain included |
| A `.compute` kernel | `pipelines.add(kernel)`, or `pipelines.kernels(MyOps::precache)` for a set |
| A VFX look known only at runtime (a summoned move, a pack loaded later) | `VFX.precache(LOOK)` on the render thread, then poll `VFX.ready()` |

**Known at load: register.** In pre-init or init (a mod's constructor or common setup). Built at the graphics start,
in parallel, while the host's loading screen shows:

```java
CgPipelinePrecache.register("mymod:runes", pipelines -> {
    pipelines.add(RUNE_MATERIAL, false);                              // a material, every pass the world draws it with
    pipelines.add("mymod:shaders/rune.shader");                       // every combination its cg_permutation names
    pipelines.add(CgCompute.load("mymod:shaders/runes.compute").kernel("Step"));
});
```

**Known later: request.** When what draws is known (a scene, an arena, an asset bundle). Built over frames inside the
warm budget (`crystalgraphics.warmBudget`, a few milliseconds a frame); hold whatever needs it until `ready()`:

```java
CgPipelinePrecache.Request arena = CgPipelinePrecache.request(pipelines -> {
    pipelines.add(FLOOR, false);
    pipelines.add(ARMOUR, false, true);                               // a material a skinned mesh draws
});
if (arena.ready()) openArena();                                       // each frame until true
```

**Too big for one frame: steps.** A request's CPU-only half on a worker first, then each step while the budget lasts:

```java
CgPipelinePrecache.Request move = CgPipelinePrecache.steps("mymod:move")
        .ahead(() -> CgKernel.ahead(MyOps::precache))                 // parsing and generating, on a worker
        .step(p -> p.add(BEAM, true))
        .step(p -> p.kernels(MyOps::precache))
        .start();
```

Textures, meshes and sounds are not pipelines: read them ahead with `CgPreload`
(`CgPreload.request(l -> l.texture(t).mesh(path))`), beside a pipeline request. The full API is
[`ENGINE_API.md`](ENGINE_API.md) § *CgWorldRenderer* and `CgPipelinePrecache`'s javadoc.

**Easy to get wrong:**

- **Name what draws, not every file.** A shader's base variant is rarely the one a draw binds (keywords, skinned, half
  size, multi-draw), and every variant of a folder is thousands of programs.
- **A collector registered in post-init is late on a client**: post-init runs after the graphics start, so it builds
  over frames and its draws are held meanwhile.
- **Never list keyword combinations by hand.** A keyword a renderer switches goes in the shader's
  `#pragma cg_permutation`, and a combination outside it compiles at its first draw. A shader with a domain that still
  misses is logged, whatever `logMisses` says.

## Checking that nothing was missed

A pipeline nothing precached is counted at its first draw. Run your content once with
`-Dcrystalgraphics.pipeline.logMisses=true`: each miss is logged with its shader, pass and keywords.

| Counter | Means |
|---|---|
| `pipeline.missed` | a draw found a pipeline nobody asked for: add it to a collector or a request |
| `pipeline.too-late` | asked for, not built in time: request it earlier |
| `material.compile-waits` | a frame waited for a program; `CgMaterialShader.compileWaits()` reads it |
| `world.unready-draws` | draws held this frame while their programs build |
| `tracker.pipelines-built` | (Vulkan) pipelines built at a draw rather than ahead |

The harness gates `--mode=vfx-precache` and `--mode=vfx-declared` hold a VFX move to 0 compile waits; a gate of your own
reads the same counters.

## First plays: warming the Java too

A precached look still runs cold Java on its first play: classes load and the JIT has not compiled its paths. The
engine rehearses every precached registry entry and system asset in a hidden system before its first play
(`CgRehearsal`), automatically. A move of your own warmed the same way, and the switches, are
[`vfx/performance.md`](vfx/performance.md) § *First plays: warming the JIT*.

## The shipped pipeline list (Vulkan)

On Vulkan a program's pipelines are built as it links, from a recorded list (Unreal's bundled PSO cache,
`CgPipelineRecipes`): the jar's `assets/crystalgraphics/pipelines/recorded.txt`, and the player's own
`crystalgraphics/cache/pipelines/recorded.txt`, which records every pipeline built at a draw. A mod does nothing: its
pipelines are recorded in the player's cache on their first launch.

**The jar's list belongs to the engine, and goes stale.** Each recipe is keyed by its program's source hash, so editing
a shipped `.shader`, an `#include` it reads or a `.compute` file orphans that program's recipes, and the next Vulkan
launch builds its pipelines at their draws. **Refresh the list after changing the engine's shaders, and before a
release**:

1. Run each of `CgRenderDemo`'s scenes (`blasts`, `beams`, `modules`, `fluid`, `blast`, `collision`, `lights`,
   `shadows`) on the NeoForge 26.2 dev client, with Minecraft running on Vulkan, all into one empty cache. From
   CrystalGUI's root, once a scene, letting each play for a minute:

   ```bash
   ./gradlew :runtime:mc:modern:neoforge:26.2:runClient -PcgAcceptEula -Dcrystalgui.autotest=true \
       "-Dcrystalgui.autotest.world=*" -Dcrystalgui.autotest.desktop=false \
       -Dcrystalgraphics.demo=<scene> -Dcrystalgraphics.cache.dir=<one empty folder>
   ```
2. Copy `<that folder>/pipelines/recorded.txt` over `core/src/main/resources/assets/crystalgraphics/pipelines/recorded.txt`.
3. Check: a cold rerun of one scene (a new empty cache) builds no pipeline at a draw: `tracker.pipelines-built` stays
   0, and the new cache's `recorded.txt` gains no line.

A new draw path the demo scenes never reach (a new renderer, a new pass) needs a scene that reaches it in step 1.
