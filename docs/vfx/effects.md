# Effects: how an effect draws

A look draws through renderers, each drawing a **material asset**. An emitter's renderers draw its particles: a
sprite, a mesh, a ribbon, an arc or a trail each ([`particles.md`](particles.md) § *Renderers*). A slot renderer draws on
one of a behaviour's slots: a beam's body, a mesh in place of a sphere ([below](#a-material-on-a-behaviours-slot-slot-renderers)).
This page covers how a draw blends with what is behind it, which faces it shows, whether it writes depth, what it
samples, and where it draws among the rest. In Unreal these are a material instance's base property overrides and a
Niagara renderer's sort order.

```java
static final CgMaterialDefinition SPARK = CgMaterialDefinition.of("mymod:materials/spark",
        "crystalgraphics:shaders/vfx/particle/spark.shader").usage(CgMaterialUsage.SPRITES).build();

static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").capacity(400).rate(120f)
        .renderer(CgVfxRenderers.sprite("sparks").material(SPARK).bind("_Color", EMBER).build())
        .build();
```

The same in a file:

```yaml
# assets/mymod/vfx/materials/spark.yaml
shader: crystalgraphics:shaders/vfx/particle/spark.shader
usage: [sprites]
```

```yaml
# in an emitter
renderers:
  - { id: sparks, kind: sprite, material: mymod:materials/spark, bind: { _Color: user.ember } }
```

## A material's render state: blend, two-sided, depth, clip

A shader authors its own `RenderState`. A material can draw it under another one, so one shader serves every way a kit
draws it: the same soft sprite added for fire and blended over for smoke. A material that names no blend draws as its
shader authors, as an Unreal instance keeps its parent's blend mode unless it overrides it.

```java
static final String SOFT = "mymod:shaders/vfx/soft.shader";

static final CgMaterialDefinition FIRE = CgMaterialDefinition.of("mymod:materials/fire", SOFT)
        .usage(CgMaterialUsage.SPRITES).blend(CgBlendMode.ADDITIVE).twoSided().build();

static final CgMaterialDefinition SMOKE = CgMaterialDefinition.of("mymod:materials/smoke", SOFT)
        .usage(CgMaterialUsage.SPRITES).blend(CgBlendMode.PREMULTIPLIED).build();
```

```yaml
# assets/mymod/vfx/materials/fire.yaml
shader: mymod:shaders/vfx/soft.shader
usage: [sprites]
state: { blend: additive, two_sided: true }
```

The same state on a material of your own, outside a look:

```java
CgMaterial glow = CgMaterial.newInstance(SOFT, CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build());
CgWorldRenderer.get().draw(mesh, glow).at(x, y, z).submit();
```

### The blend modes

| `CgBlendMode` | The shader's line it stands for | Unreal | The fragment writes |
|---|---|---|---|
| `OPAQUE` | `Blend OFF` | Opaque | its colour |
| `ALPHA` | `Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA` | Translucent | straight colour and its alpha |
| `PREMULTIPLIED` | `Blend ONE ONE_MINUS_SRC_ALPHA` | AlphaComposite | colour already times its coverage, and the coverage |
| `ADDITIVE` | `Blend ONE ONE` | Additive | light; its alpha is unused |
| `MODULATE` | `Blend DST_COLOR ZERO` | Modulate | what is behind, times its colour |
| `SUBTRACT` | `Blend ONE ONE`, `BlendEquation REV_SUB` | (none) | what it takes away from behind it |

A shader drawn under a mode draws exactly as if it had authored that line: its fog, its decode into the HDR scene,
how smoke in front dims it, and whether its glow merges into its own draw all follow the mode. `SUBTRACT` is fogged
as `ADDITIVE` is, and its glow (a codeless Emissive pass) adds rather than subtracts.

### Two-sided and depth

```java
CgMaterialDefinition.of(id, SHADER).twoSided()                    // both faces: Unreal's Two Sided
CgMaterialDefinition.of(id, SHADER).cull(CgCullState.FRONT)       // the inside of a dome
CgMaterialDefinition.of(id, SHADER).depthWrite(true)              // its Forward pass writes depth
```

Two-sided applies to every pass of the material, its glow and its haze included.

### Clipped: solid where it covers

`masked(threshold)` is Unreal's Masked: a fragment below the threshold is discarded, the rest writes depth, so what
draws later behind it stays hidden. The shader says where it is solid with `cg_Clip(value)`
([`../SHADERS.md`](../SHADERS.md) § *Depth from a transparent material*).

```java
static final CgMaterialDefinition INK = CgMaterialDefinition.of("mymod:materials/ink",
        "mymod:shaders/vfx/ink.shader").usage(CgMaterialUsage.SPRITES).blend(CgBlendMode.ALPHA).masked(0.33f).build();
```

### What it costs

- **Each blend mode, and a clip, compiles once**: the shader's programs under that mode, made the first time a material
  draws with it, and shared by every material asking for it. Warm them with the look (`VFX.prepare(LOOK)`).
- **Cull and depth write compile nothing**: they are the draw's pipeline state, on the same programs.

## Constants, binds and keywords

A material holds its constants (`value`, `color`, `texture`); a renderer reads user parameters into it per draw
(`bind`), and may hold a value of its own for its draws alone.

```java
static final CgMaterialDefinition TRAIL = CgMaterialDefinition.of("mymod:materials/streak",
        "crystalgraphics:shaders/vfx/trail/glow.shader").value("_Tail", 0.1f).value("_Flow", 6f).build();

CgVfxSlotRenderer.of("streak", STREAK).material(TRAIL)
        .bind("_Color", CgEnergyWave.SHELL)              // a user parameter, read every draw
        .bind("_Radius", CgEnergyWave.RADIUS, 2.6f)      // times 2.6
        .size(0.45f).build();
```

- **Nothing binds by name.** A material reads a user parameter only where a renderer binds it: `_Shell` and `shell` are
  unrelated until bound.
- **A bind names a parameter the look declares**, `user.<name>`; one it does not declare fails at load, naming the
  parameters it has. A `particles.*` bind fails at load too: a particle's own attributes are read in its material.
- **A value bound per draw is a row** ([`../SHADERS.md`](../SHADERS.md) § *Per-draw properties*), so two effects
  playing different values still share one material and batch.
- `keyword(name)` on a material enables a `#pragma cg_feature`. The keyword of its usage is enabled for it
  (`FX_ON_SPRITES` on a sprite renderer's, `FX_ON_MESH_PARTICLES` on a mesh renderer's), and a slot's kind where the
  shader declares it (`FX_ON_TUBE` on a tube slot).

## Textures and samplers

A material's texture is an image file, or one made in code and **provided** under an id:

```java
CgMaterialDefinition.of(id, SHADER).texture("_Sheet", "mymod:textures/vfx/smoke_8x8.png")

CgMaterialRegistry.provide("mymod:textures/vfx/fire_ramp", FIRE_RAMP::texture);     // a texture made in code
CgMaterialDefinition.of(id, SHADER).texture("_Ramp", "mymod:textures/vfx/fire_ramp")
```

A texture samples as its spec says. A material can sample it another way instead, so one image is loaded once and
used clamped by one material and tiled by another. Unreal calls this a material's sampler source.

```java
static final String NOISE = "mymod:textures/vfx/noise.png";

CgMaterialDefinition.of("mymod:materials/mask", MASK_SHADER)
        .texture("_Mask", NOISE, CgSampler.Filter.BILINEAR, CgSampler.Address.CLAMP)    // a soft edge that never wraps
CgMaterialDefinition.of("mymod:materials/scroll", SCROLL_SHADER)
        .texture("_Scroll", NOISE, CgSampler.Filter.TRILINEAR, CgSampler.Address.WRAP)  // the same upload, tiled
```

| `CgSampler.Address` | A coordinate past 0 to 1 reads | Unreal |
|---|---|---|
| `WRAP` | the texture again | Wrap |
| `CLAMP` | the edge texel | Clamp |
| `MIRROR` | the texture again, every other copy flipped | Mirror |

- **An image decodes as sRGB** unless its settings file says `colour: linear` (below).
- **A provided id is provided once**; a second supplier under it throws. Provide it before the first draw naming it.
- **On a texture with no mips**, `BILINEAR` and `TRILINEAR` sample its base level.
- **Anisotropy is clamped** to what the player's GPU has.

### Cooked textures: BC7, cooked once

An effect kit ships its textures cooked: BC7 with every mip, a quarter of RGBA8's memory, and nothing encoded at load.
Name the `.cgtex` where you would name the PNG.

Cook a folder of PNGs with CrystalGraphics' task (Windows, as texconv is):

```bash
./gradlew cookTextures -Pcook.from=<kit>/textures -Pcook.to=<kit>/cooked
```

```yaml
# smoke_normal.texture.yaml, beside smoke_normal.png: only what differs from the defaults
colour: linear          # srgb (default) | linear: data, not colour
mips: false             # default true
compression: none       # bc7 (default) | none: no .cgtex, load the PNG
```

- **Colour is sRGB by default**, as Unreal's `SRGB` flag is. A normal map, a flow map or any mask read as numbers is
  `linear`.
- **Where the GPU cannot sample BC7** (macOS) the blocks are decoded to RGBA8 as the texture loads: the same picture,
  four times the memory.
- **The mips are the file's**: a spec's own mipmaps are not generated over them.

## Meshes carry colour and a second UV

A mesh a renderer draws (a mesh slot's `mesh`, a mesh renderer's `meshes`) is `SPATIAL`: position, normal, vertex
colour and two UV sets, as Unreal's static meshes are. A glTF's `COLOR_0` and `TEXCOORD_1` load into it; where a file
has none, colour is white and the second UV the first. A shader reads them as `cg_Color` and `cg_TexCoord1`:

```glsl
#pragma cg_feature SECOND_LAYER

void vertex(out v2f o) {
#ifdef SECOND_LAYER
    o.detailUv = cg_TexCoord1;          // a second texture laid out on its own UVs: CC2's two-texture layers
#endif
    o.tint = cg_Color.rgb;
    ...
}
```

```java
CgVfxSlotRenderer.of("slash", SLASH).mesh("mymod:models/slash.glb")
        .material(CgMaterialDefinition.of("mymod:materials/slash", LAYERED).keyword("SECOND_LAYER").build()).build();

CgMeshLoader.provide("mymod:models/crescent", () -> CgMeshLods.of(CRESCENT));   // a mesh made in code, by id
```

## Flipbooks: which frame of a sheet a particle shows

A sprite renderer draws a frame of its material's sheet on each particle. Its `sub_image` says where the frames are and
which one each particle shows: Niagara's Sub UV Animation over its sprite renderer's sub-images.

```java
CgVfxRenderers.sprite("puffs").material(SMOKE_SPRITE)
        .subImage(Map.of("size", List.of(8, 8), "blend", true))      // 64 frames over each puff's life, blended
        .build();
```

```yaml
sub_image: { size: [4, 4], mode: loop, rate: 24, random_start: true }   # flames: 24 frames a second, each its own
sub_image: { size: [4, 4], mode: random, rate: 8 }                       # a new frame 8 times a second
sub_image: { size: [8, 8], frames: [16, 31] }                            # rows 2 and 3 alone
```

| Key | Plays | Unreal |
|---|---|---|
| `mode: linear` (the default) | first to last over each particle's life | Linear |
| `mode: loop`, `rate` | round the range at that rate, last back to first | Infinite Loop, by frames a second |
| `mode: random`, `rate` | a whole frame of the range, drawn again that often; 0 keeps the first | Random |
| `mode: direct` | the frame each particle carries, set by its emitter's `sub_uv_animation` ([particles](particles.md) § *Values a particle carries for its material*); frame 0 where it sets none | Direct Set |
| `frames: [first, last]` | only those frames, both included | Start and End Frame Range Override |
| `blend` | each frame fading into the next; never for random | Sub UV Blending Enabled |
| `random_start` | each particle's loop at a frame of its own | Random Start Frame |

- **Frame 0 is the image's top left**, rows going down. A sheet exported from Unreal, Effekseer or a CC2 dump plays as
  authored.
- **A loop and random run on the particle's age in seconds**, so a long-lived particle loops more times than a
  short-lived one; linear runs on its life.
- A rect table (`rects`) is not built yet and fails at load.
- A shader of your own reads the same: `_cg_SubImage`, `_cg_SubUV` and `_cg_SubImages`, through `fx_particle.glsl`'s
  `fx_subuv_frames` (a direct frame from `CG_PARTICLE_DRAWN(n).color.x`) and `fx_subimage_uv`, as the sprite factory
  does.

## How a sprite faces

A sprite renderer's `facing` and `alignment` are Niagara's:

| `facing` | The quad | `alignment` | Its top |
|---|---|---|---|
| `camera` (the default), `camera_plane` | lies in the screen | `unaligned` (the default) | as the facing turns it |
| `camera_position` | turns to the eye round world up | `velocity` | along its motion, stretched by `_cg_Stretch` |
| `custom` | lies across `_cg_FacingVector` | `custom` | along `_cg_AlignmentVector` |

```java
CgVfxRenderers.sprite("ripples").material(RING).facing("custom").build();       // flat on the ground
CgVfxRenderers.sprite("flames").material(FLAME).alignment("custom").build();    // standing upright
static final CgMaterialDefinition RING = CgMaterialDefinition.of("mymod:materials/ring", SPRITE_GLOW)
        .usage(CgMaterialUsage.SPRITES).value("_cg_FacingVector", 0f, 1f, 0f, 0f).build();
```

- **The custom vectors are Niagara's `Particles.SpriteFacing` and `SpriteAlignment`.** Particles carry no such
  attributes yet, so the material's `_cg_FacingVector` and `_cg_AlignmentVector` hold one for every particle: (1, 0, 0)
  unless set, Niagara's default.
- `camera_distance_blend` is not built yet and fails at load.

## Global parameters: one value every material reads

A value set once and read by every material whose shader reads it: a skill darkening the whole stage, the light of a
move's backdrop. Unreal's material parameter collections, which Niagara's systems and a sequence's `global` track set.

```java
public static final CgParameterCollection SKILL = CgParameterCollection.of("skill");
public static final CgParameterCollection.Scalar DARKEN = SKILL.scalar("darken", 0f);

DARKEN.set(0.6f);                    // from an effect's tick: every material reading it, this frame
```

```glsl
#pragma cg_use globals
...
fragColor.rgb *= 1.0 - CG_GLOBAL_skill_darken;
```

- A value is the same for every effect and every material, unlike a renderer's `bind`, which is per effect.
- Declare the parameters before the first set. The shader side is [`../SHADERS.md`](../SHADERS.md)
  § *Global parameters*.

## Where a draw goes: its hint and its sort layer

Within its effect, every draw ranks by its **hint**: an emitter renderer's `sort_order_hint`, a slot renderer's
material `order`. Lowest first; draws of one hint keep the look's order. The engine's looks use four bands of 64:

| Band | Hints | For |
|---|---|---|
| smoke | 0 to 63 | smoke, dust, soft matter |
| light | 64 to 127 | light pools on what is round the effect |
| distortion | 128 to 191 | haze and shock fronts: a material with a Distortion pass |
| surface | 192 to 255 (`CgVfxLook.SURFACE`) | the effect itself: its body, glows, sparks |

```java
CgVfxRenderers.sprite("glow").material(GLOW).sortOrderHint(CgVfxLook.SURFACE + 12).build()   // over the core
CgVfxRenderers.sprite("core").material(CORE).sortOrderHint(CgVfxLook.SURFACE + 4).build()
CgMaterialDefinition.of("mymod:materials/haze", HAZE).order(2 * CgVfxLook.BAND).build()  // a slot's haze
```

- **A surface draw goes after its own effect's distortion**: a draw ranked 192 or more whose material has no Distortion
  pass is applied after its effect's haze, so the haze never smears it.
- A draw can also leave its effect's place among every other translucent draw in the world by its material's
  `sort_layer` (`background`, `default`, `effects`, `overlay`): Unreal's translucency sort priority.
- An imported kit's render layers are hints: a kit converted from another engine keeps its draw order this way.
- **At most 256 draws a look**; a look past that throws as it builds.

### Effects that sort as one: a sort group

A move is often several effects (a spiral, a shockwave, a flash) whose draws are authored to stack in one order.
Apart, each sorts by its own distance, so whichever is farther draws first and the stacking flips as the camera moves.
In one `CgVfxSortGroup` they draw as one: at one place, their draws ranked together by hint, then the effect that
joined first. Unreal's components sharing a translucency sort priority, and CC2's render layer numbers held across a
whole clip.

```java
CgVfxSortGroup move = new CgVfxSortGroup();                     // one per move played
VFX.play(new CgVfxSystemInstance<>(SPIRAL, x, y, z).sortGroup(move));
VFX.play(new CgVfxSystemInstance<>(SHOCKWAVE, x, y, z).sortGroup(move));   // a hint of 196 here draws before SPIRAL's 204
```

```java
move.at(casterX, casterY + 1, casterZ);                         // sorts at the caster, not its members' middle
effect.sortGroup(null);                                          // that effect sorts alone again
```

- **Where it sorts** among other effects is the middle of its members' origins, worked out once a frame, unless
  `at(...)` fixes it; `atMembers()` goes back to the middle.
- **A hint means the same in every member's look**, so author one numbering across the move.
- **At most 256 draws** across every look its members play; a look past that throws as it joins.
- **GPU particles keep their system's group**: their draws go as one batch for the whole system, ordered by depth.
  What a group orders is every other draw.
- A member leaves when it dies. Render thread.

## A material on a behaviour's slot: slot renderers

A system played by a native behaviour (the energy wave) draws material assets on that behaviour's slots, fed by its
user parameters: the slot's counterpart of an emitter's renderers. In the file:

```yaml
# assets/mymod/vfx/systems/blue_wave.yaml
effect: crystalgraphics:energy_wave
slots:
  - id: shell
    slot: crystalgraphics:wave/body          # a slot the behaviour declares
    material: mymod:materials/shell          # a material asset
    bind: { _Shell: user.shell }             # read per draw from a user parameter
    size: 1.15                               # times the slot's own size
  - id: rock
    slot: crystalgraphics:wave/head
    material: mymod:materials/rock
    mesh: mymod:models/rock.glb              # a mesh slot only: drawn in place of its sphere
    quality: medium                          # drawn from Medium up
```

In Java, the same entry:

```java
CgVfxSlotRenderer shell = CgVfxSlotRenderer.of("shell", CgBeamBody.BODY).material(SHELL)
        .bind("_Shell", CgEnergyWave.SHELL).size(1.15f).build();
CgVfxLook look = CgEnergyWave.kamehameha().derive().slot(shell).build();
```

- **They draw by their material's `order`**, then in their list order.
- **A tube slot draws the geometry its material's shader names**: `Tags { "Tube" = "Volume" }` draws each chunk on a
  sphere round it (a shader summing its own chunk's light), `"Closed"` caps each chunk (a shader tracing the view ray
  through it). A shader tagged so, on any other slot, fails at load, unless it declares `FX_ON_TUBE` and draws other
  geometry as a variant.
- **Binds read user parameters alone**, `user.<name>`; a `particles.*` bind fails at load.
- An unknown slot, or a mesh on a slot that is not a mesh slot, fails at load, naming the entry.

### A light on a slot

A slot can light what is round it instead of drawing a material: a `light` in place of `material`, `size` and `mesh`.
It is Unreal's point light (`LightColor`, `Intensity`, `AttenuationRadius`, `SourceRadius`, `SourceLength`) or spot
light (`InnerConeAngle`, `OuterConeAngle`), added to the world's lights each frame the slot draws, and `bind` sets its
fields by the same names. The ground under a beam floods with its colour, a wall beside a blast glows, a lamp throws a
cone.

```yaml
slots:
  - id: light
    slot: crystalgraphics:blast/light
    light: { light_color: { argb: 0xFFFFC080 }, intensity: 4, attenuation_radius: 2.2 }
  - id: head_light
    slot: crystalgraphics:wave/head
    light: { intensity: 4, attenuation_radius: 9 }
    bind: { light_color: user.glow }         # the effect's own colour, read every frame
  - id: lamp
    slot: mymod:bolt/lamp
    light: { type: spot, inner_cone_angle: 10, outer_cone_angle: 25, intensity: 40, attenuation_radius: 30,
             source_radius: 0.5 }
```

```java
CgVfxSlotRenderer glow = CgVfxSlotRenderer.light("light", CgVfxBlast.LIGHT)
        .lightColor(0xFFFFC080, 1f).intensity(4f).attenuationRadius(2.2f).build();
CgVfxSlotRenderer head = CgVfxSlotRenderer.light("head_light", CgBeamBody.HEAD)
        .intensity(4f).attenuationRadius(9f).bind("light_color", CgEnergyWave.GLOW).build();
CgVfxSlotRenderer lamp = CgVfxSlotRenderer.light("lamp", LAMP)               // LAMP = CgVfxSlot.mesh("mymod:bolt/lamp")
        .spot(10f, 25f).intensity(40f).attenuationRadius(30f).sourceRadius(0.5f).build();
```

| Field | Default | Means |
|---|---|---|
| `light_color` | white | its colour, linear; `{ argb, intensity }` is decoded from sRGB, then times its intensity and alpha |
| `intensity` | 1 | how bright it is where it is the slot's size `s` away; past that it falls off as the square of the distance |
| `attenuation_radius` | 1 | its reach, times `s`: nothing past it |
| `source_radius` | 0 | its source a ball this many times `s` across: a surface it touches or sinks into is lit, where a point in the surface's plane only grazes it |
| `source_length` | 0 | its source a line this many times `s` long, along the slot's +Y; mesh slots only |
| `type` | `point` | `spot` for a cone along the slot's +Y; mesh slots only |
| `inner_cone_angle`, `outer_cone_angle` | 0, 44 | a spot's half angles in degrees: full inside the inner, nothing past the outer |

- **Where it is, and `s`**: on a mesh slot, at the draw's point, `s` the size the effect places the slot's sphere at;
  its brightness is also times the draw's strength, the third of its four numbers (a charge ball's fade, a blast
  light's burn). On a tube slot, a chain of tube lights along the path, one per half
  reach, following its bends, `s` each stretch's ring radius: the ground under a beam lit along its whole length.
- **Only mesh and tube slots take one.** A light with a `material`, `size` or `mesh`, an entry with neither a material
  nor a light, a bind to any other field, cone angles on a point, and a spot or `source_length` on a tube slot fail at
  load, naming the entry.
- **Its axis** on a mesh slot is the transform's +Y, as the engine's spawn shapes': turn the slot's transform to aim a
  spot.
- **It fades with its effect** (`presence()`) and drops out at a quality tier below its `quality`, as a material does.
- It lights the opaque scene behind each pixel, the surface's facing rebuilt from depth and its colour from the
  lightmap: Minecraft keeps neither for its world. A particle's own light (a light renderer) takes its drawn radius as
  its source's.

## Easy to get wrong

- **The mode is not the shader's queue.** A shader in the opaque queue drawn `ADDITIVE` still draws with the opaque
  pass; give a blended shader `Queue = "Transparent"`.
- **The fragment writes for its mode.** A shader whose colour is straight drawn `PREMULTIPLIED` comes out too bright at
  its edges: multiply by the alpha, or draw it `ALPHA`.
- **A depth write and a clip are refused together**, and either on a shader whose `"Depth"` tag already owns its depth
  (`Clip` or `Prepass`): the shader fails to load, the log naming the state.
- **A material serves the usages it lists**, a static mesh where it lists none. A sprite renderer on a material that does
  not list `sprites` fails at load, and so does a usage its shader is not compiled for (`FX_ON_<USAGE>`).
- **Hints rank across bands**: a surface draw of hint 192 draws after a smoke draw of hint 40 whatever was added first.
- **Sorting together needs a group.** Two effects played at one point still sort apart, each by its own distance;
  only a `CgVfxSortGroup` ranks their draws together.
