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
| `vfx.particle` | The particle engine, Niagara-shaped (plan `vfx-particles`): `CgVfxEmitter` (one kind of particle: spawn, launch, a module stack, curves over life; a look holds its own), `CgVfxEmitterInstance` (one running: spawning, the implicit solver, deterministic per seed), `CgVfxModule` (the physics pieces, data only: gravity, drag, wind, curl-noise turbulence, buoyancy, updraft, ground, spin), `CgVfxParticleSet` (struct-of-arrays state), `CgVfxAir` (the system's gusting wind), `CgVfxCurlNoise` (Bridson curl noise). Runs inside `vfx.sim`'s trace zone; 2,000 particles with every module cost about 0.27 ms a tick |
| `vfx.render` | Reusable draw primitives: `CgVfxTube` (a path as chunked tube draws), `CgVfxRibbons` (stateless GPU particles: ribbons a shader places from their index; 32 segments, enough for a lightning channel), `CgVfxQuads` (the shared `CgMesh.quads(1024)` a shader places from the frame's particle records, `#pragma cg_use particle`: one draw per emitter through `CgVfxFrame.particles`, its range the live particles. A camera-facing quad per particle is `CgVfxFrame.billboard`, on `CgMesh.quads(1)`, its own draw so the world renderer sorts alpha-blended ones back to front. The quads and ribbons carry no vertex data (`#type none`): `fx_common.glsl`'s `FX_QUAD_*` and `FX_RIBBON_*` read a vertex's place from `CG_VERTEX_ID`. Vertex pulling rather than instancing a unit quad: the world renderer's instancing already carries one object record per draw, a particle instance kind would open a closed engine enum, and a 4-vertex instance wastes most of a vertex batch) |
| `vfx.element` | Parts many effects share, as Sparking Zero reuses them: `CgVfxExplosion` (billows, debris specks, embers and ink streaks: an emitter and a layer each, its colours declared on the effect's schema, added to a look with `.add(kit)`) |
| `vfx.effect.<family>` | One package per family of effects: `beam` (`CgEnergyWave`, with the `kamehameha()`, `finalFlash()` and `galickGun()` looks: one set of layers, three palettes) |

| Shaders | Hold |
|---|---|
| `shaders/lib/vfx/` | The GLSL libraries, `fx_`-prefixed: `fx_common` (hashes, gradient and value noise with their fractals, warped turbulence, flicker, voronoi, erf, tonemap, `FX_CAMERA` -- the ONE copy: the showcase's `shaders/demo/vfx_common.glsl` includes it and adds only its studio lighting), `fx_tube` (the path texture, tube placement, ray against an axis), `fx_volume` (analytic core and glow volumes), `fx_depth` (scene depth; only a depth reader includes it), `fx_ribbon` (ribbon hashes and placement), `fx_lightning` (a bolt's midpoint-displaced channel, its return strokes, and the core-halo-glow profile across it), `fx_particle` (placing a particle's quad or stroke: the record index, a turned camera-facing corner, a velocity's heading) |
| `shaders/vfx/<family>/` | One directory per family, mirroring `vfx.effect.<family>`, named by slot (`body_*`, `head_*`) |
| `shaders/vfx/particle/` | Shared particle looks drawn from the particle records: `speck` (ragged debris), `spark` (a glowing disc streaked along its motion, as bright as it is hot), `arc` (an ink stroke round the emitter's source) |
| `shaders/vfx/air/` | Shared by any family, the air bending: `haze` (a shimmer round something hot, on a sphere) and `haze_tube` (along a beam), `shock` (a blast's shock front, refracting at its silhouette). Each redraws the scene behind it from `cg_SceneColor`, so they take `CgVfxLayer.ORDER_DISTORTION` and `.from(CgQuality.MEDIUM)`. That order sits after smoke, light and glow volumes, which they bend on one copy of the target, and before the sharp layers (`ORDER_SURFACE`, `_BANDS`, `_CORE`), which are never bent nor copied, so a bright body is never smeared into the air round it. Every effect sorts as one group at its origin in `CgSortLayer.EFFECTS`, its layers' orders ordering nothing outside it, so a haze bends every effect farther than its own and none nearer |
| `shaders/vfx/smoke/` | Shared by any family: `billow` (a cel-shaded explosion billow as a real mesh: a sphere displaced by Voronoi domes, opaque with a depth prepass, contour strokes where the surface turns from the eye -- the anime look, as built in UE5 Niagara; all noise per vertex), `smoke_puff` (realistic: a lit puff on a billboard that depth-tests its own ball's surface, so it meets the ground in a curve) |

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
- **Additive layers each take an order of their own** (`CgVfxLayer.ORDER_*`, within the effect's group: nothing
  outside the effect sees it), so their chunks batch.
- **A chunk's bounds cover everything its shader draws**: radius, cap push and displacement.
- **A volume that integrates along the view ray must cover each pixel once**: a bent tube's far wall does not, so such a
  tube layer is `volume()` and sums only its own chunk's rings on a sphere.
- **Libraries name no `cg_*` or `CG_*`**: an include compiles ahead of the frame block, so it takes values as
  arguments, and what must name them is a macro.
- **Meshes are made in `CgVfxSystem` only**, so a change to how meshes are made is one edit.
- **An effect never reads a player's setting.** `CgVfxSystem` applies `CgGraphicsSettings` to every effect: density
  thins each emitter, the quality tier skips layers in `CgVfxFrame`, the clock follows pause, freeze and tick rate. An
  effect only declares what is detail: a layer `.from(CgQuality.MEDIUM)`, an emitter `.optional()` (halved at Low).
