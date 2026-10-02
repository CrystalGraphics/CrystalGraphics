# vfx — the VFX engine

Effects a mod plays in a world, drawn through `CgWorldRenderer`: simulated on a fixed tick, customised by looks and
parameters, built from reusable primitives. Design and steps: private plan `crystalgraphics/vfx-engine.md`.

```java
CgVfxSystem vfx = new CgVfxSystem();
CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
wave.aim(dx, dy, dz).target(tx, ty, tz);
// every frame, before the world stages record:
vfx.update(seconds);
vfx.submit(CgWorldRenderer.get());
```

## Where things go

**A new file lands in the package that owns its concern; the root holds only what runs effects.**

| Package | Owns |
|---|---|
| `vfx` | Running effects: `CgVfxSystem` (fixed tick, submission, every mesh and material the engine draws), `CgVfxEffect` (a playing effect), `CgVfxFrame` (what an effect draws through), `CgVfxMomentListener` (an effect's named moments, framed: the capture hook) |
| `vfx.look` | How an effect looks and behaves: `CgVfxLook`, `CgVfxLayer` (one draw, in a slot), `CgVfxSchema` and `CgVfxParam` (what an effect reads), `CgVfxValues` (a value for each) |
| `vfx.path` | Centrelines: `CgVfxPath` (spline, arc-length rings, rotation-minimising frames), `CgVfxPathTexture` (paths on the GPU) |
| `vfx.sim` | Simulation parts: `CgVfxStream` (the hose model, homing by proportional navigation) |
| `vfx.render` | Reusable draw primitives: `CgVfxTube` (a path as chunked tube draws), `CgVfxRibbons` (stateless GPU particles: ribbons a shader places from their index) |
| `vfx.effect.<family>` | One package per family of effects: `beam` (`CgEnergyWave`, with the `kamehameha()`, `finalFlash()` and `galickGun()` looks: one set of layers, three palettes) |

| Shaders | Hold |
|---|---|
| `shaders/lib/vfx/` | The GLSL libraries, `fx_`-prefixed: `fx_common` (hashes, gradient and value noise with their fractals, warped turbulence, flicker, voronoi, erf, tonemap, `FX_CAMERA` -- the ONE copy: the showcase's `shaders/demo/vfx_common.glsl` includes it and adds only its studio lighting), `fx_tube` (the path texture, tube placement, ray against an axis), `fx_volume` (analytic core and glow volumes), `fx_depth` (scene depth; only a depth reader includes it) |
| `shaders/vfx/<family>/` | One directory per family, mirroring `vfx.effect.<family>`, named by slot (`body_*`, `head_*`) |

## Seeing every moment

An effect announces the moments of its life (`CgEnergyWave.MOMENT_*`), each framed by a point and a radius, and
`CgVfxSystem.onMoment` hears them. The harness turns that into a contact sheet of the whole lifecycle, each moment
photographed on the frame it happens:

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=vfx-spheres" -Dcrystalgraphics.harness.vfx.moments=kamehameha -Dcrystalgraphics.harness.fixedDelta=0.0166667
# -> gl-debug-harness/harness-output/vfx-spheres/vfx-spheres-NN-kamehameha-<moment>.png, then it exits
# moments=finalFlash or galickGun for that lane, moments=true for all three
```

**A new effect declares its moments as constants and calls `moment(...)` as it crosses each**, framing the part that
matters; that is what makes it debuggable at full speed.

## Rules

- **Draw through `CgWorldRenderer`**, never a renderer of its own on the stages. A new instance kind is asked of the
  render-graph owner, and only when per-draw records are measured as the cost.
- **One property snapshot per material per stage.** Per-effect variation is per-draw data (`custom` slots, the path
  texture) or another layer; never set a property between two submits of one material.
- **Additive layers each take a priority of their own** (`CgVfxLayer.PRIORITY_*`, a share of a 4-bit field every
  world consumer uses), so their chunks batch.
- **A chunk's bounds cover everything its shader draws**: radius, cap push and displacement.
- **A volume that integrates along the view ray must cover each pixel once**: a bent tube's far wall does not, so such a
  tube layer is `volume()` and sums only its own chunk's rings on a sphere.
- **Libraries name no `cg_*` or `CG_*`**: an include compiles ahead of the frame block, so it takes values as
  arguments, and what must name them is a macro.
- **Meshes are made in `CgVfxSystem` only**, so a change to how meshes are made is one edit.
