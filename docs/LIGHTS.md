# Lights — the world's lights, and how they light Minecraft

`CgWorldLights` (`world.lights()`) adds Unreal's local lights to the world: points, spots and tubes, from the CPU or
appended by a kernel. They light everything on screen: Minecraft's blocks and entities, and the engine's own draws.
Tens of thousands a frame are affordable.

They are **deferred**: nothing changes Minecraft's draw calls or its shaders. Read § 2 before adding anything that
must light Minecraft's world.

## Contents

1. [Adding lights](#1-adding-lights)
2. [How they light Minecraft: deferred, from what Minecraft drew](#2-how-they-light-minecraft-deferred-from-what-minecraft-drew)
3. [How a light falls off](#3-how-a-light-falls-off)
4. [Day and night](#4-day-and-night)
5. [Tens of thousands: the light grid](#5-tens-of-thousands-the-light-grid)
6. [Lights from effects](#6-lights-from-effects)
7. [Shadows](#7-shadows)
8. [On transparent surfaces and particles](#8-on-transparent-surfaces-and-particles)
9. [In the air](#9-in-the-air)
10. [What they do not do yet](#10-what-they-do-not-do-yet)
11. [Checking and measuring](#11-checking-and-measuring)

---

## 1. Adding lights

Add every light every frame it shines, before the world records (a tick function in `CgTickGroup.SUBMIT`, or the
same place a draw is submitted):

```java
CgWorldLights lights = CgWorldRenderer.get().lights();
lights.add(x, y + 1.5, z, 6f, 0xFFFF9030, 40f, 0f);                                 // a point
lights.add(x, y + 6, z, 12f, 0xFFFFF0D0, 300f, 0f).spot(0f, -1f, 0f, 20f, 35f);     // a spot, pointing down
lights.add(midX, midY, midZ, 24f, 0xFF60A0FF, 40f, 0f).tube(dirX, dirY, dirZ, 30f); // a tube, 30 blocks long
lights.add(x, y + 0.1, z, 3f, 0xFFFFB040, 2f, 0f).source(0.1f);                     // a glowing ball on the ground
```

`add(x, y, z, radius, argb, intensity, exponent)`:

| Argument | Means |
|---|---|
| `x, y, z` | absolute world position, in doubles |
| `radius` | blocks to where it reaches nothing (Unreal's AttenuationRadius) |
| `argb` | its colour, `0xAARRGGBB` in sRGB; alpha multiplies it |
| `intensity` | its brightness: under the inverse square, the surface colour it adds one block away facing it is `colour × intensity / π` |
| `exponent` | 0 for the inverse square (physical, the default everywhere); any other is Cascade's `(1 - d²/r²)^exponent` (§ 3) |

| Then | Makes the light added last |
|---|---|
| `.spot(dx, dy, dz, inner, outer)` | a spot along the direction, full within `inner` degrees, nothing past `outer` (half angles, Unreal's InnerConeAngle and OuterConeAngle, clamped as SpotLightComponent clamps them) |
| `.tube(dx, dy, dz, length)` | a line `length` blocks long along the direction, centred where it was added (Unreal's SourceLength) |
| `.source(radius)` | a ball `radius` blocks across (Unreal's SourceRadius), so a surface it touches or half sinks into is lit |

- **A light lives one frame.** Add it again each frame it shines.
- `addLinear(x, y, z, radius, r, g, b, exponent)` takes a linear colour already times its intensity: what code that
  works out its colour adds.
- Past `CAPACITY` (65,536, `-Dcrystalgraphics.world.lights`) lights a frame, the rest are dropped and counted.
- A kernel's lights: an `Appender` (`world.lights().add(appender)`), its positions from the firing's eye
  (`cameraX()`, `_LightEye`); `CgWorldLights`' javadoc has the example.

## 2. How they light Minecraft: deferred, from what Minecraft drew

An old forward renderer lights an object in that object's own draw: its shader loops over the lights. That is
impossible here. Minecraft draws its own blocks and entities, version by version, and Sodium, Embeddium and Iris
replace that drawing.

So the lights are **deferred**, as Unreal and every engine since about 2010 light a scene. Minecraft draws the world
first. Then one full-screen pass lights every pixel from what it left. A deferred engine reads a G-buffer its
objects wrote. Minecraft writes none, so the pass **rebuilds one from Minecraft's depth and colour** every frame:

| A G-buffer stores | Rebuilt from |
|---|---|
| Position | the depth at the pixel, unprojected |
| Normal | the neighbouring depths: whichever neighbour each way is the smaller step, so a block's edge does not bend it (Wicked Engine's improved normal reconstruction), snapped to a world axis within 10 degrees, as blocks are |
| Albedo | the scene's colour over Minecraft's lightmap value at the light's block: what the surface would be under white light |
| Roughness, metalness | nothing: no specular |

In GL terms, after Minecraft's level has drawn:

```
bind Minecraft's depth (cg_DepthBuffer)
glBlendFunc(GL_DST_COLOR, GL_ONE)            // result = scene + scene × added
draw a full-screen triangle with world_lights.shader:
    p = unproject(depth); n = normal from the neighbouring depths
    for each light in p's light-grid cell:
        added += colour × falloff × N·L / π / lightmap(light's block)
    out = added
```

**What follows from it:**
- **The lights only add.** Minecraft's own light (torches, the sky, its baked ambient occlusion and darker side faces)
  is never darkened. A shadow (plan `render-shadows`) will only withhold one of our lights.
- **Anything new that lights Minecraft's world goes into this pass**, never into a draw's shader: shadows, light
  functions, new light kinds.
- **Only the front surface of each pixel exists.** Water, glass, particles and smoke behind or in front of it are not
  in the rebuilt buffer, so they need their own path (§ 7).
- **Normals are per block face**, not per texture pixel, and Minecraft's baked face shading stays in the albedo: a
  light falls a little dimmer on side faces and in corners, which usually reads as right.
- Everything drawn into the world's depth is lit alike: Minecraft's blocks and entities, and the engine's own opaque
  draws.

## 3. How a light falls off

| Exponent | Falloff | Up close |
|---|---|---|
| **0** (the default) | the inverse square in blocks, with Unreal's square centimetre as the bias, faded to nothing at the radius | grows without limit as a surface nears it: a firefly touching a sleeve flares |
| any other | Cascade's `(1 - d²/r²)^exponent` | flat near the light, never past its colour: a soft, capped glow |

**Use the inverse square.** It is Unreal's, Niagara's and Cascade's default, and what a light looks like: a pool from
afar, a hot spot up close. Tame a light by its intensity, never by switching to an exponent.

Up close, the hot spot on a dark surface clips to white: Minecraft's frame has no tonemapper.

N·L comes from the light's centre for a point, the nearest point of its line for a tube. A light with a source
radius wraps N·L over its sphere above the surface's horizon (Unreal's `SphereHorizonCosWrap`, `CapsuleLight.ush`),
so one whose centre sits in or just behind a surface still lights it.

## 4. Day and night

Minecraft has no exposure: its noon is as bright as its moonlight is dim, so a light bright enough for a night would
vanish at noon. A light is **authored for the night** and fades under an open sky by day, as an eye adapted to the
sun sees it:

- full at night, and wherever no sky reaches it (caves, inside);
- a tenth under a full day's open sky (`-Dcrystalgraphics.world.lightsDayFade`, 1 for never).

## 5. Tens of thousands: the light grid

Lights are binned into Unreal 5's light grid before the pass: 64-pixel tiles, 32 depth slices. A parent grid four
cells a side is culled first, then each cell from its parent's list, a work group a cell where compute runs. Cells
behind the scene are skipped by its depth pyramid (Unreal's HZB cull, `-Dcrystalgraphics.world.lightsHzb=false` to
turn it off). Each cell's list is whole, from a budget shared by every cell that grows when a frame overflows it.

A light smaller on screen than Unreal's `r.MinScreenRadiusForLights` (0.03, `-Dcrystalgraphics.world.lightsMinScreenRadius`)
is not drawn: past 50 blocks for one reaching under a block and a half, past 33 times its reach for larger ones.

30,000 lights over a floor cost 0.43 ms on an RTX 4070 SUPER, binning and lighting together.

## 6. Lights from effects

Effects add lights without calling `add`:

- **A particle light**: an emitter's `light(...)` and a `light` renderer: a light a particle (Cascade's Light module,
  Niagara's light renderer), its source radius the particle's drawn radius. [`vfx/particles.md`](vfx/particles.md)
  § *Renderers: how an emitter's particles draw*.
- **A slot light**: a slot renderer that lights instead of drawing a material, a point or a spot with a source radius
  and length. [`vfx/effects.md`](vfx/effects.md) § *A light on a slot*.

## 7. Shadows

A light added from the CPU casts shadows: the blocks and entities between it and a surface withhold it. Nothing is
drawn from the light's side, so there are no shadow maps and no caster passes: each pixel traces rays to its lights,
as Unreal 5's **MegaLights** does, ported from its shaders pass by pass.

```java
lights.add(x, y, z, 8f, 0xFFFF9030, 40f, 0f);                    // casts shadows
lights.add(x, y, z, 8f, 0xFFFF9030, 40f, 0f).shadowed(false);    // Unreal's bCastShadows off: the grid alone, no rays
```

| Pass | Unreal (MegaLights) | Here |
|---|---|---|
| Sample | `GenerateLightSamplesCS`: 4 samples a half-resolution pixel, a weighted reservoir over its tile's lights, weight `log2(luminance + 1)`; a sample of the same light merged into the next one's ray | `world_lights_sample.shader`, over the light grid's cell, the shadowed lights alone; every pixel by default (below) |
| Trace | screen trace (hierarchical, 1 m, a hit within 0.005 of the depth), then the global distance field | screen trace (24 steps over 1 block, `crystalgraphics.world.lightsScreenTrace`, the same thickness), then `CgWorldVoxelWindow`'s distance field (blocks, 128x96x128 round the camera), then `CgWorldEntityBoxes`' entities as capsules, only those its 16x16 tile can meet (Unreal's capsule shadow tiles, `world_lights_tiles.shader`; `crystalgraphics.world.lightsEntityTiles=false` keeps all), then our meshes' distance fields |
| Shade | `ShadeLightSamplesCS`: each pixel picks a nearby half-resolution pixel by bilinear, depth and normal weight; each visible light lit exactly, times its samples' weights | `world_lights_shade.shader` |
| Temporal | `DenoiserTemporalCS`: history clamped in YCoCg to the neighbourhood, 4 to 12 frames by confidence, moments kept | `world_lights_temporal.shader`, one history for the first firing (`crystalgraphics.world.lightsShadowFrames`); the sun's and block light's visibility clamped and accumulated the same way, with their moments |
| Spatial | `DenoiserSpatialCS`: where still noisy, a disk of 4 (8 after a disocclusion) samples by depth, normal and luminance | `world_lights_denoise.shader` (`crystalgraphics.world.lightsShadowSpatial`, pixels); the visibility by the same rule from its own moments, its difference standing for luminance |
| Composite | adds into scene colour | `world_lights.shader` adds it beside the unshadowed lights |

- **The sun** is not one of MegaLights' lights (Unreal's `r.MegaLights.DirectionalLights` is 0): our objects shadow it
  through a **cascaded shadow map**, Unreal's for a movable directional light, while it is up and something of ours is
  drawn. Four cascades to `crystalgraphics.world.sunShadowDistance` (96 blocks), each 3 times the depth of the last
  and a tenth into the next, a sphere round each slice of the view snapped to whole texels, in one atlas of
  `crystalgraphics.world.sunShadowResolution` (1024) a cascade; our opaque casters drawn into it with Unreal's caster
  bias, read with its `Manual3x3PCF` and soft comparison. On our objects' pixels a ray toward it also crosses the
  blocks and entities. So our objects receive its shadow from blocks, entities and each other; Minecraft's world
  receives it from our objects alone, its own blocks keeping the light Minecraft baked. A shadow takes the sun's
  direct share (`crystalgraphics.world.sunShare`, 0.6) out of the pixel's sky light, through Minecraft's own lightmap,
  so the shade is Minecraft's. Our pixels are found from the host's depth snapshotted before our opaque pass.
  `crystalgraphics.world.sunShadows=false` turns it off; `--mode=lights-sun` is its gate.
- **Minecraft's block light** is shadowed by our objects the same way. Each light-giving block the voxel window finds
  (a torch, glowstone, lava: a local maximum of block light the world says emits) within
  `crystalgraphics.world.blockShadowReach` blocks (48) joins the list as a black light (`CG_LIGHT_MINECRAFT`), while
  something of ours is drawn. Each sample picks one in proportion to the light Minecraft's flood gives the pixel from it
  (its level less the blocks between) and traces it; the share that gets through scales the pixel's block light, again
  through the lightmap. Casters are as for the sun. A block placed after its section was read waits for the window's
  refresh; `--mode=lights-torch` is the gate.
- **What casts**: Minecraft's blocks within the window, its living entities within 64 blocks, anything on screen, and
  our meshes through their **mesh distance fields** (Unreal's `MeshDistanceFieldUtilities.cpp`, traced as
  `DistanceFieldShadowingShared.ush` does). `CgMeshDistanceFields` builds one per mesh and submesh on first draw: 16
  voxels a block, at most 48 an axis, the nearest triangle's distance, inside by a back-face vote. Each frame the
  opaque draws within 96 blocks, at most 256, become casters (of the sun's map too, taken from
  `crystalgraphics.world.sunCasterReach` blocks, 48, toward the sun past each cascade); a ray
  sphere-traces each field it crosses, 32 steps at most, ignoring a hit within two voxels of its start (the self-shadow
  bias). Counter `world.lights-casters`; `--mode=lights-casters` is the gate.
- **Normals**: our opaque materials write their vertex normal into `cg_world.normals` (the `CG_NORMAL_TARGET`
  variant, `CgPipeline.normalTarget()`), and every pass reads it where drawn, as Unreal's passes read the G-buffer's;
  Minecraft's pixels rebuild theirs from depth, held to a world axis. On our pixels the sun fades over a face's last
  few degrees toward it, so a face edge-on to the sun holds still.
- **What does not**: a particle's light (Unreal's simple lights cast none), a slot light with `cast_shadows: false`,
  any light under `-Dcrystalgraphics.world.lightsShadows=false`.
- **Resolution and rays**: `crystalgraphics.world.lightsDownsample` is Unreal's `r.MegaLights.DownsampleMode`: 0 traces
  every pixel, 1 every other (2x1), 2 a quarter (2x2); `crystalgraphics.world.lightsSamplesPerPixel` its
  `NumSamplesPerPixel`, 1, 2 or 4 samples each. Unreal's default, 4 samples on a 2x2 pixel, is a ray a screen pixel and
  leans on TSR to hide the 2-pixel steps; Minecraft's frame has no temporal anti-aliasing, so the default is the same
  budget at full resolution: 1 sample a pixel, the frames averaging the rest. Minecraft's block light takes
  that sample's one ray too, and on our pixels the sun's. Standing over the shadows demo's plaza at 2000x1080 the sample pass took 12-13 ms at 4
  samples a pixel and 3.2-3.4 at 1 (RTX 4070 Super, Minecraft 1.20.1).
- **The blocks' trace** sphere-steps the window's distance field, and within a block and a half of solid walks its grid
  (Amanatides and Woo): an empty block in one step, a block with solid octants octant by octant.
- **Departures from Unreal**: a linear screen trace rather than a hierarchical one; no visible-light hash guiding the next frame's samples; lights that cast no shadow stay on the grid's exact pass
  rather than being sampled; the shading confidence is 1; every pixel traced by default (above).
- The gate is `--mode=lights-shadow`: a wall of blocks between a light and the floor, the light behind it gone and in the
  open the same as unshadowed. GPU zones `world.sunShadow`, `world.lightsTiles`, `world.lightsSample`,
  `world.lightsShade`, `world.lightsTemporal`, `world.lightsDenoise`.
- **What a ray's parts cost, in game**: `-Dcrystalgraphics.demo=shadows -Dcrystalgraphics.demo.shadowsBisect=true
  -Dcrystalgraphics.trace.channels=gpu` leaves each part out in turn (`crystalgraphics.world.lightsSampleSkip`: 1 the
  sun, 2 block light, 4 the lights, 8 our meshes, 16 the screen trace, 32 the blocks, 64 the entities) while the player
  stands over a shadow, and logs `[shadows-bisect]` the sample pass's GPU time without each. `--mode=lights-shadows-bench`
  is the harness's version of that view (animals, eye height and the sun's angle by `-D`).

## 8. On transparent surfaces and particles

The deferred pass lights only the front opaque surface of each pixel. What is transparent (glass, smoke, every lit
particle) reads **Unreal's translucency lighting volume** instead, its default for particles: its cost is the volume's,
not every transparent pixel's.

```glsl
// nothing to write: a lit Forward pass (the default; "Lighting" = "Unlit" opts out) takes it through cg_Lit
fragColor = vec4(albedo, alpha);                         // in a transparent firing, lit by the lights round it too
vec3 extra = cg_LocalLightAt(cg_FragmentPosition());     // the same light by hand, for a material that opted out
```

- **The volume** (`env/compute/light_volume.compute`, GPU zone `world.lightsVolume`): two cascades of 64 cells a side,
  the first to `crystalgraphics.world.lightsVolumeInner` blocks (15), the second to `...lightsVolumeOuter` (50), each
  the sphere round its slice of a fixed 45-degree view, weighted to its far end and snapped to whole cells
  (TranslucentLighting.cpp:270-370). Filled each transparent firing that draws something, after the light grid: each
  cell inside the view's frustum takes every light of its light-grid cell, lit as the deferred pass lights a surface
  less N.L (the same falloff, day fade and lightmap scale), over pi; a light that casts shadows is traced from the cell
  through the blocks, the entities and our meshes. Cells outside the frustum stay black.
- **Read non-directionally**, as Unreal's `Volumetric NonDirectional` translucency: the ambient band times
  pi x 0.282095, a quarter of what a surface squarely facing the light takes. `cg_Lit` multiplies the colour by
  `1 + volume` beside the lightmap, as the deferred pass multiplies the scene, the inner cascade blending into the
  outer over its edge (`TransitionScale` 6). Bound at `CgBindingPoints.LOCAL_LIGHTS_TEXTURE_UNIT` (`cg_LocalLights`) on
  the world renderer's transparent passes; black elsewhere, so an opaque pass is lit by the deferred pass alone.
- **Departures from Unreal**: every light of a cell traced every frame, where MegaLights samples a few and blends
  frames; no marking of the cells a translucent pixel reads; the ambient band alone, until a directional consumer.
- `crystalgraphics.world.lightsVolume=false` turns it off; `--mode=lights-volume` is the gate. `CgLightsDemo` shows it:
  glass round the carousel, smoke behind the duel.

## 9. In the air

Smoke, gas and haze in the world's media (`CgWorldMedia`'s froxels: whatever an injector puts there) glow round the
lights, with shafts where the lights' shadows cut them: Unreal's volumetric fog lighting its froxels.

```java
CgWorldHaze haze = new CgWorldHaze().box(x - 24, y, z - 20, x + 24, y + 18, z + 20).density(0.03f, 4f);
CgWorldRenderer.get().media().add(haze);      // a haze to see the lights in; VFX smoke lights the same way
```

- **Scattering** (`light_volume.compute`'s `Scatter`, between the injectors and the integration): each froxel holding
  extinction takes its light-grid cell's lights as `LightScatteringCS` does (VolumetricFog.usf:1085-1169): colour x
  falloff x day fade x the Henyey-Greenstein phase toward the eye (g 0.2, the height fog's default) x the froxel's
  scattering (its extinction, albedo white). A shadowed light is traced from the froxel, at most
  `crystalgraphics.world.lightsAirShadowRays` (4) a froxel; past them a froxel's lights are unshadowed.
- **History** (Unreal's temporal reprojection, VolumetricFog.usf:856-921, :1173-1180): each firing lights a froxel at a
  Halton 2, 3, 5 point inside it and blends 0.9 of what the last firing's grid held at its middle, reprojected through
  the last firing's view, so a narrow beam is averaged over each cell instead of read at its centre. Dropped at the first
  firing, after a resize or a firing with nothing in the air, and outside the last grid.
  `crystalgraphics.world.lightsAirHistory=false` lights froxel middles alone.
- **Integration** (`media.compute`): Unreal's `FinalIntegrationCS`, Frostbite's energy-conserving sum, into `cg_Media`'s
  rgb (the light scattered toward the eye) beside its alpha (what gets through, `cg_MediaTransmit`).
- **Composite** (`world_media_light.shader`, GPU zone `world.mediaLight`): the scattered light added over the opaque
  scene at each pixel's depth, out to the grid's far end where there is sky, before the transparent passes. The media
  is recorded whenever an injector is added, transparent draws or not.
- **Departures from Unreal**: the opaque scene is not dimmed by the transmittance (what puts smoke in the air draws its
  own look; only the light is added); no conservative-depth rejection of the history and no supersampling where it
  misses; a budget of shadow rays a froxel.
- `crystalgraphics.world.lightsAir=false` turns it off; `--mode=lights-air` is the gate. At 320x240 with one light the
  media pass took 0.10 ms and the composite 0.02 (RTX 4070 Super); the grid grows with the screen. `CgLightsDemo` stands
  in a haze.

## 10. What they do not do yet

| Missing | Unreal's answer | Plan |
|---|---|---|
| Rect lights, IES profiles, light functions | rect lights, IES atlases, light function atlases | none yet |
| Specular | GGX from the G-buffer's roughness | none: Minecraft has no roughness |

## 11. Checking and measuring

| Gate | Holds |
|---|---|
| `--mode=lights-grid` | every light word, every cell's list and the falloffs against Java, at every tier and on Vulkan |
| `--mode=lights-shadow` | a light behind a wall of blocks withheld, in the open kept, on GL and Vulkan |
| `--mode=lights-sun` | our box shadows the host's ground, blocks off screen shadow our box, open ground kept, on GL and Vulkan |
| `--mode=lights-torch` | a torch's light on the host's ground withheld behind our box, kept in the open, on GL and Vulkan |
| `--mode=lights-casters` | our box shadows the host's ground and our ball our slab off their distance fields, the ground beside kept, no shadow more than 3 pixels past the exact one, the sun's edge across the ball steady as the camera turns, on GL and Vulkan |
| `--mode=vfx-lights` | every particle light (where, how far, how bright, its block and sky light, its source radius) against a Java gather |
| `--mode=lights-bench` | the cost: `-Dcrystalgraphics.harness.lights=10000` and profile `world.lights`, `world.lightsCoarse`, `world.lightsCells`, `world.lightsApply` |

`CgWorldLightsTest` holds the view-bob split. In Minecraft, `CgRenderDemo`'s `lights` scene shows every kind; its HUD
reads how many lights a frame dropped and the cells' budget. Its `shadows` scene shows every caster on every receiver
(our shapes, the player, mobs, Minecraft's blocks, the sun and a torch placed by its east pillar), O turning every
shadow off and on to compare.
