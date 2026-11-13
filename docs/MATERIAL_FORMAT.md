# Writing materials — the `#domain` format

How to write a `.shader` material: what it looks like, what each part does, and how the engine turns it into
everything that draws it. Read top to bottom the first time; after that, jump to [the reference](#reference).

> **Already know Unreal?** A file here is an Unreal material written as text. `#domain` is the Material Domain,
> `Material { }` the Details panel, `surface()` the outputs of the main material node, `VertexFactory` what Unreal
> picks from the component drawing it, and `Primitive` the Slate element a UI material is drawn on.

---

## 1. The idea in one paragraph

You describe **what a surface looks like**, never **how to draw it**. You write one function, `surface()`, that says
the colour, how see-through it is, whether it glows, whether it bends what is behind it. The engine reads which of
those you wrote and builds every pass the material needs: the colour pass, a glow pass if it glows, a distortion pass
if it bends, a depth pass if it is solid. It lights it, fogs it, shades it with the smoke in front of it, and blends it,
as your `Material { }` block says. You never write `gl_Position`, a blend function or a second pass.

```glsl
// mymod:shaders/glass.shader
#domain surface
Material { BlendMode Translucent }
Properties { _Tint ("Tint", color) = (0.6, 0.8, 1, 0.3) }
void surface(in v2f i, inout CgSurface s) {
    s.baseColor = _Tint.rgb;
    s.opacity = _Tint.a;
}
```

```java
CgMaterial glass = CgMaterial.load("mymod:shaders/glass.shader");
pass.draw(mesh, glass).transform(model);
```

That is a complete, lit, fogged, sorted, translucent glass.

---

## 2. A file, top to bottom

```glsl
#domain surface                                   // 1. what it is: always the first line
#include "crystalgraphics:shaders/lib/noise.glsl" // 2. GLSL libraries you call
Material { BlendMode Masked  Faces Both }         // 3. how it draws
Keywords { MOSS ("Mossy") }                       // 4. compile-time switches
Properties {                                      // 5. the values a material instance sets
    _Albedo ("Albedo", sampler2D) = "white"
    _Moss   ("Moss colour", color) = (0.3, 0.5, 0.2, 1)
}
struct v2f { vec2 uv0; };                         // 6. what reaches the pixel from each vertex
float moss(vec2 uv) { return value_noise(uv * 8.0); }   // 7. helper functions, any you like
void surface(in v2f i, inout CgSurface s) {       // 8. the look
    vec4 albedo = texture(_Albedo, i.uv0);
    s.baseColor = albedo.rgb;
    s.opacityMask = albedo.a;                     //    Masked: cut where this is below 0.5
#ifdef MOSS
    s.baseColor = mix(s.baseColor, _Moss.rgb, moss(i.uv0));
#endif
}
```

Every block is optional except `#domain` and the domain's function. Comments are `//` and `/* */`. There is no
`#pragma`, `Pass`, `Tags` or `Queue`: each has a better-named home below, and writing one fails with a message saying
where it went.

---

## 3. Domains: what the file is

The first line says what kind of thing the file draws, and so which function you write.

| `#domain` | Draws | You write | Section |
|---|---|---|---|
| `surface` | Anything in the world: meshes, particles, beams, volumes | `surface(in v2f i, inout CgSurface s)` | [§ 4](#4-surface-materials) |
| `post` | Over the whole screen, at a point in the frame | `surface(in CgScreen i, inout CgPostSurface s)` | [§ 7](#7-post-materials) |
| `ui` | An element on a canvas: a box, a stroke, text | `surface(in CgUiInput i, inout CgUiSurface s)` | [§ 8](#8-ui-materials) |
| `global` | One of the engine's own passes: raw GLSL | `vertex(out v2f o)` and `fragment(in v2f i, out vec4 c)` | [§ 9](#9-global-the-engines-own) |

---

## 4. Surface materials

### 4.1 `surface()`: the look

`s` arrives holding the defaults. **Write only the outputs your material has.** Which ones you write decides which
passes it draws in (§ 4.4), so writing nothing you do not need is also what keeps it cheap.

| Output | Type | Default | What it is |
|---|---|---|---|
| `baseColor` | `vec3` | `(1, 1, 1)` | Its colour: lit by the world, or shown as written under `ShadingModel Unlit` |
| `opacity` | `float` | `1` | How much it hides what is behind it (translucent blend modes) |
| `opacityMask` | `float` | `1` | Where it is solid: kept at or above `MaskClip` (`Masked`, `ClipDepth`, `DepthPrepass`) |
| `emissive` | `vec3` | `(0, 0, 0)` | Light it gives off, linear and unlit; past 1, it blooms |
| `normal` | `vec3` | `(0, 1, 0)` | World-space normal, lighting a translucent surface (`TranslucencyLighting Surface`) |
| `refraction` | `vec2` | `(0, 0)` | How far it bends what is behind it, in screen UV (0.02 = 2% of the screen) |
| `refractionSplit` | `float` | `0` | The bend split by colour: red bends `1 + split` as far, blue `1 - split` |
| `refractionDepth` | `float` | the pixel's eye depth | Where the bend starts, for a haze traced inside a volume |
| `pixelDepthOffset` | `float` | `0` | Pushes its depth farther, in blocks (never nearer) |
| `customData` | `vec4` | `(0, 0, 0, 0)` | Data a post material reads: an outline's colour and id |
| `shadingModelId` | `uvec2` | `(1, 0)` | A model and type a post material reads |
| `light` | `vec2` | the draw's | The `(block, sky)` light it is lit by: for a particle, its own |
| `volumeDepth` | `float` | `0` | A volume's eye distance, so what draws after it knows where smoke starts |
| `depth` | `float` | the fragment's own | The eye depth it stands at: a volume traced inside its hull writes where the ray enters (`cg_EyeDepth(point)`), nearer or farther |

```glsl
// A glass pane that tints, bends a little, and glows where it is cracked
void surface(in v2f i, inout CgSurface s) {
    float crack = texture(_Cracks, i.uv0).r;
    s.baseColor = _Tint.rgb;
    s.opacity = 0.3;
    s.refraction = (texture(_Ripple, i.uv0).rg - 0.5) * 0.01;
    s.emissive = _CrackGlow.rgb * crack * 4.0;      // linear, past 1, so it blooms
}
```

What the engine hands every material, by name:

| Name | Is | Unreal |
|---|---|---|
| `CG_CAMERA_POSITION` | the eye, in the space positions are in (camera-relative world) | Camera Position |
| `cg_PixelRay()` | the unit view ray from the eye through this pixel (`surface()` only) | Camera Vector, reversed |
| `cg_EyeDepth(point)` | a point's eye depth: what `s.depth` takes | |
| `cg_DepthFade(distance)` | 0 where this pixel meets what is behind it, 1 `distance` blocks in front: soft particles | DepthFade |
| `cg_LinearEyeDepth(gl_FragCoord.z)` | this pixel's eye depth | Pixel Depth |
| `gl_FragCoord.xy / CG_RESOLUTION` | this pixel's screen UV | Screen Position |
| `CG_SCENE_COLOR(uv)`, `CG_SCENE_EYE_DEPTH(uv)` | the scene's colour and depth behind it | Scene Color, Scene Depth |
| `CG_TIME` | seconds | Time |

Naming a scene read is enough: the engine copies the scene for you. A surface reading `CG_SCENE_COLOR` says how far past itself it reads, `Material {
SceneColorMargin 0.05 }`, so the copy covers it.

### 4.2 `v2f`: what reaches the pixel

`v2f` holds what each vertex hands to the pixel, interpolated across the triangle. **Without a `vertex()` function**,
name its fields after these and the engine fills them:

| Field | Filled with |
|---|---|
| `vec2 uv0` … `uv3` | the vertex's texture coordinates |
| `vec4 color` | the vertex's colour |
| `vec3 normal` | its world-space normal |
| `vec3 world` | its camera-relative world position |
| `vec2 light` | the draw's `(block, sky)` light |

Any other name needs a `vertex()` to fill it. `flat int id;` passes a value un-interpolated (an integer must be
`flat`); `noperspective vec2 p;` interpolates in screen space.

### 4.3 `vertex()`: moving vertices

```glsl
void vertex(inout CgVertex v, out v2f o);
```

The engine fills `v` from whatever draws the material (§ 5), your `vertex()` runs, then the engine projects `v.world`.
**Move `v.world` to move the vertex**: that is World Position Offset, and because every pass projects the same
`v.world`, the depth pass and the colour pass always agree.

```glsl
// Grass bending in the wind: the top sways, the root stays
void vertex(inout CgVertex v, out v2f o) {
    v.world.x += sin(CG_TIME * 2.0 + v.position.x) * 0.1 * v.uv0.y;
    o.uv0 = v.uv0;
}
```

| `CgVertex` | Is |
|---|---|
| `position` | object space, as the factory read it (a particle's: its offset from the particle's centre) |
| `normal` | world space |
| `color` | white where there is none |
| `uv0` … `uv3` | texture coordinates |
| `world` | camera-relative world position: the one value projected |

### 4.4 Which passes it draws in

Worked out once, when the material loads, from what `surface()` names:

| The material… | Draws in |
|---|---|
| is `Opaque` or `Masked` | the depth pass, the base pass, the world-normals target |
| is translucent and writes `baseColor` or `opacity` | the translucent pass (or the separate one, or the overlay, by `TranslucencyPass`) |
| writes `emissive` | the glow: folded into its colour's draw where it can, or a draw of its own |
| writes `refraction` | the distortion pass |
| writes only `emissive` | the glow alone, no colour draw |
| writes only `refraction` | the distortion pass alone: a pure heat haze is one draw |

"Writes" means `surface()` (or a helper it calls) names the output, even under a keyword: a keyword switches a value,
not a pass.

### 4.5 `Material { }`: how it draws

Name–value pairs; every one has a default, so most materials set one or two.

| Property | Values (default first) | What it does |
|---|---|---|
| `BlendMode` | `Opaque`, `Masked`, `Translucent`, `AlphaComposite`, `Additive`, `Modulate`, `Subtract` | How it combines with what is behind it. `Masked` is solid with holes cut by `opacityMask`; `AlphaComposite` is premultiplied; `Additive` and `Subtract` ignore `opacity` and leave what is behind's alpha as it was |
| `ShadingModel` | `WorldLit`, `Unlit` | Lit by the world's light, or shown as written |
| `TranslucencyLighting` | `Volume`, `Surface` | A translucent material lit as a volume, or as a surface by its `normal` |
| `Faces` | `Front`, `Back`, `Both` | Which side of each triangle draws. `Back`: a volume's far wall |
| `TranslucencyPass` | `Standard`, `AfterDistortion`, `Separate`, `Overlay` | When a translucent material draws: with the others, after the bend, after everything translucent, or over the world |
| `DepthTest` | `On`, `Off`, `Strict` | Hidden by what is in front. `Strict` keeps a pixel's first fragment only (a beam traced twice) |
| `DepthWrite` | by blend mode | Writes depth: on when solid, off when translucent |
| `DepthPrepass` | `Off`, `On` | A translucent material's solid core drawn into depth first (`opacityMask`): smoke |
| `ClipDepth` | `Off`, `On` | A translucent material writing depth where `opacityMask` passes: ink, debris |
| `MaskClip` | `0.5`, a number or a float property | The `opacityMask` threshold |
| `Fog` | `On`, `Off` | Fogged with distance |
| `Media` | `On`, `Off` | Dimmed by the smoke and dust in front of it |
| `ColorSpace` | `sRGB`, `Linear` | What its colours are written in |
| `SceneColorMargin` | a number | How far past its own pixel it samples the scene, as a share of the screen's height: required where it reads `CG_SCENE_COLOR` (0.001 for its own pixel alone) |
| `Sky` | `Off`, `On` | Drawn first, at the far plane behind everything, whatever its mesh's size |
| `PerDrawValues` | `Off`, `On` | Each property may be set per draw, by the draw's row (a VFX look's) |
| `VertexFactory` | `Mesh`, … | What draws it (§ 5) |

A setting that would do nothing is refused, naming the one that would make it work: `opacity` under `Opaque`,
`MaskClip` without a cutting mode, `normal` without `TranslucencyLighting Surface`.

### 4.6 `Keywords { }`: switches

```glsl
Keywords { MOSS ("Mossy")  SHAPE ("Shape") = BOX | CONE }
...
#ifdef MOSS        // on or off
#ifdef SHAPE_BOX   // a one-of: SHAPE_BOX or SHAPE_CONE, never both
```

```java
CgMaterial mossy = stone.keyword("MOSS", true);           // another program, made once and kept
CgMaterial cone = stone.keyword("SHAPE_CONE", true);
```

Each combination is its own program, compiled once: use a keyword for a choice made per material, a property for a
value that changes. A keyword the renderer flips at each draw, rather than a material choosing it once, is marked
`switched` (`DEPTH ("Writes depth", switched)`): every combination of the switched ones is built ahead, so no draw waits
on a compile. A one-of is always built ahead.

### 4.7 `Properties { }` and `Buffers { }`: what it reads

```glsl
Properties {
    _Tint   ("Tint", color, hdr) = (1, 1, 1, 1)          // hdr: an editor lets it past 1
    _Rough  ("Roughness", float, range(0, 1)) = 0.5      // range: an editor's slider
    _Albedo ("Albedo", sampler2D) = "white"
    _Wind   ("Wind", vec4) = (1, 0, 0, 0)
}
```

Types: `float`, `int`, `vec2`, `vec4`, `color`, `sampler2D`, `sampler2DArray`, `sampler3D`, `samplerCube` (no `vec3`:
use `vec4`). A material instance sets them by name (`instance.set1f("_Rough", 0.2f)`).

`Buffers { }` reads what a compute kernel wrote:

```glsl
struct Spark { vec4 positionLife; vec4 velocitySeed; };
Buffers { SPARKS ("Sparks", Spark, readonly) }
...
Spark p = SPARKS(CG_VERTEX_ID >> 2);                     // in vertex() or surface()
```

```java
sparks.buffer("SPARKS", kernelOutput);
```

---

## 5. Vertex factories: what draws a surface material

### 5.1 What a factory is

A material does not choose where its vertices come from: **the thing that draws it does.** A rock is a mesh; a smoke
puff is a particle turned to face the camera; a beam is a tube bent along a path. The same `surface()` could be
drawn by any of them. A **vertex factory** is that thing, as the material sees it:

- **it fills `CgVertex`** before your `vertex()` runs: the corner of a particle's quad, already faced and flipbooked;
  a mesh's vertex, already placed by its transform; a tube's vertex, already skinned onto its path;
- **it brings what its draws bind**: the particle records, the tube's path texture, the engine buffers they live in;
- **it can hand the material extra values** about what is being drawn, its *parameters*: the particle's age and
  colour (`cg_Particle`), where on the beam's path the vertex is (`cg_Tube`).

You name one in `Material { }`, and the engine compiles your material for it:

```glsl
Material { BlendMode Translucent  VertexFactory Sprites }
```

### 5.2 The engine's factories

| `VertexFactory` | What draws it | It hands you |
|---|---|---|
| `Mesh` (default) | A mesh: `pass.draw(mesh, material)`, static, instanced or skinned | the mesh's vertices |
| `Procedural` | A draw of a vertex count, no mesh | nothing: your `vertex()` assigns `v.world`, usually from `Buffers { }` and `CG_VERTEX_ID` |
| `Sprites` | A particle renderer's camera-facing quads (`CgVfxRenderers.sprite`); a beam's or ribbon's strips | each corner placed, faced and flipbooked; `cg_Particle` |
| `MeshParticles` | A particle renderer's meshes (`CgVfxRenderers.mesh`) | the mesh's vertices placed by the particle (`v.position` the mesh's own); `cg_Particle` |
| `VfxMesh` | A VFX mesh slot (`CgVfxFrame.mesh`): a unit sphere or the layer's mesh, placed by the slot | the mesh's vertices; `cg_VfxMesh` |
| `Billboard` | A VFX billboard slot (`CgVfxFrame.billboard`): one camera-facing quad, a lone particle | the quad's corner (`v.position` -1..1); `cg_Billboard` |
| `Ribbons` | A VFX ribbons slot (`CgVfxFrame.ribbons`): stateless strips of 32 segments, placed by the material | nothing: your `vertex()` assigns `v.world` from `cg_Ribbon` |
| `PathRibbons` | A VFX path-ribbons slot (`CgVfxFrame.pathRibbons`): stateless strips riding a beam's path | nothing: your `vertex()` assigns `v.world` from `cg_PathRibbon` and the path's rings |
| `GridVolume` | A VFX grid slot (`CgVfxGrid3D`): the box round a grid's tiles, drawn on its far wall | the box; `cg_Grid`; the grid atlas bound as `_GridView`, `_GridLight`, `_GridPages` |
| `FluidVolume` | A VFX fluid slot (`CgVfxFluid`): the box round a fluid's tiles, drawn on its far wall | the box; `cg_Fluid`; the fluid atlas bound as `_FluidShade`, `_FluidRestA`/`B`, `_FluidPages` |
| `Sequence` | A prefab part a clip plays on a sequence particle (`lib/vfx/sequence.glsl`); listed after `VfxMesh` | the part's vertices placed by its node; the material declares `FX_CLIPS` |
| `Tube` | A VFX tube slot: a path drawn as chunks of rings, ends open | the tube's vertices; `cg_Tube`; `uv0` = (blocks along, 0..1 around) |
| `TubeClosed` | A tube slot, its chunks capped | as `Tube`; an end cap's centre has angle -1 |
| `TubeVolume` | A tube slot, each chunk on a sphere round it | the sphere's vertices; `cg_Tube` |

**`cg_Particle`** is the particle the vertex belongs to, readable in `vertex()` and `surface()`:

| `cg_Particle.` | Is |
|---|---|
| `color` | its colour, linear; alpha its opacity |
| `relativeTime` | age over lifetime, 0..1 |
| `random` | 0..1, fixed for its life |
| `velocity` | xyz its direction, w its speed (blocks a second) |
| `size`, `positionAndSize`, `spriteRotation` | its width and height; its centre and radius; its turn |
| `subUVCoords0`, `subUVCoords1`, `subUVLerp` | its two flipbook frames and the blend between them |
| `dynamicParameters[4]` | Niagara's dynamic material parameters: `cg_dynamic_parameter(cg_Particle, i, default)` |
| `light` | the world's light where it is: a lit particle is lit by it without you writing `s.light` |
| `opacity`, `heat` | its record's opacity and heat, as its module wrote them |
| `record` | its record: `CG_PARTICLE_SEED(cg_Particle.record)`, `CG_PARTICLE_AGE(...)` and the rest of `particle.glsl` |

`Sprites` also hands `vertex()` **`cg_SpriteAxes`**: the placed vertex's right, up and normal in the world. A strip's
side is its right, so a trail that widens pushes `v.world` along `cg_SpriteAxes[0]`.

**`cg_Vfx`** is the effect drawing it, readable in both functions under any VFX factory: `cg_Vfx.age` (seconds),
`cg_Vfx.seed` (fixed for the effect), `cg_Vfx.row`, and `cg_Vfx.presence`. You never apply `presence`, the
fade of an ending: the VFX factories apply it to all the material adds.

**`cg_VfxMesh`** and **`cg_Billboard`** are the slot's own values, readable in both functions: `.size` (the layer's
size; a billboard's half size in blocks) and `.values`, the four the effect documents for the slot (an intensity, a
progress, a particle's life and seed). `cg_Billboard.right` and `.up` are the camera's axes, for a `vertex()` turning
the quad.

**`cg_Grid`** and **`cg_Fluid`** are the volume to march: `.index` its place in the atlas, `.cell` its cell in
blocks, `.lo` and `.hi` the box, `.corner` the grid's low corner, `.size` the layer's size; a grid's `.extinction`, a
fluid's `.weight` (its first UV grid's). The march is the material's (`fx_scatter_box`, `lib/vfx/sparse.glsl`); it names the
depth its light came from with `s.volumeDepth`.

**`cg_Ribbon`** and **`cg_PathRibbon`** say where the vertex lies on its strip: `.index` the strip, `.along` 0..1 down
it, `.side` -1 or 1 across it. Those three are the vertex stage's: pass what the pixel needs in your `v2f`.
`cg_Ribbon.size` and `.values` are the slot's, as `cg_VfxMesh`'s; `cg_PathRibbon.row`, `.header` and `.origin` find
the path in `_FxPath` (`fx_ring_at`), and `.intensity` is the slot's. Copy it once, `CgPathRibbon r = cg_PathRibbon;`:
its header is a texture read.

```glsl
// A strip down each ribbon, facing the eye
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Ribbons }
struct v2f { float side; };
void vertex(inout CgVertex v, out v2f o) {
    CgRibbon r = cg_Ribbon;
    vec3 p = (CG_OBJECT_TO_WORLD * vec4(fx_sphere_dir(vec2(r.index * 0.1, 0.3)) * r.along, 1.0)).xyz;
    v.world = fx_ribbon_vertex(p, mat3(CG_OBJECT_TO_WORLD) * vec3(0.0, 0.0, 1.0), CG_CAMERA_POSITION, 0.05, r.side);
    o.side = r.side;
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(1.0 - abs(i.side)); }
```

```glsl
// A puff that fades as it ages
Material { BlendMode Translucent  VertexFactory Sprites }
Properties { _Puff ("Puff", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    s.baseColor = cg_Particle.color.rgb * texture(_Puff, i.uv0).rgb;
    s.opacity = cg_Particle.color.a * (1.0 - cg_Particle.relativeTime);
}
```

**`cg_Tube`** is where on the path the vertex is:

| `cg_Tube.` | Is |
|---|---|
| `ring` | the ring it sits on: `position` (from the origin), `radius`, `tangent`, `arc` (blocks along the path), `normal`, `intensity`. A volume's: its chunk's first |
| `angle`, `cap` | 0..1 around the tube; -1 on the first ring, 1 on the last, 0 between |
| `origin` | the effect's origin, camera-relative: `cg_Tube.origin + cg_Tube.ring.position` is the ring's centre |
| `header` | the path's ring count, length, seed and age |
| `row`, `first`, `owned`, `size` | the path's row in `_FxPath`, the chunk's first ring, how many it owns, the layer's size: what tracing a ray through the rings reads |

```glsl
// A beam's skin, pushed out from its axis where a surge passes
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Tube }
struct v2f { float along; };
void vertex(inout CgVertex v, out v2f o) {
    v.world += v.normal * 0.1 * cg_Tube.ring.intensity;
    o.along = cg_Tube.ring.arc;
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(fract(i.along)); }
```

**Only what `surface()` names reaches the pixel.** Reading `cg_Particle.color` in `surface()` passes the colour and
nothing else, so naming one field costs one value; `vertex()` reads any of them free.

### 5.3 Writing your own factory

A factory is a file. `VertexFactory mymod:Ribbons` reads **`assets/mymod/shaders/factories/Ribbons.factory`** the
first time a material names it (a bare name, like `Sprites`, is the engine's:
`crystalgraphics:shaders/factories/Sprites.factory`; open those for working examples).

```glsl
// assets/mymod/shaders/factories/Ribbons.factory
Factory { VertexType none  Parameters cg_Ribbon }          // its program's vertex format; its parameters' name
EngineBuffers { myribbons }                                 // the engine buffers its draws bind
Tags { "Ribbon" = "Flat" }                                  // tags its renderer reads (optional)
Properties { _Points ("Points", sampler2D) = "black" }      // values its draws bind: a material may not declare them

Declarations {                                              // GLSL every material drawn by it gets
    #include "mymod:shaders/ribbon.glsl"
    RibbonPoint cg_Ribbon;
}

Parameters {                                                // what a material may read of cg_Ribbon in surface()
    float width;
    flat int segment;                                       // integers are flat
    vec3 frame.normal;                                      // a nested field, dotted
}

void fill(inout CgVertex v) {                               // fills every field of v, world last
    cg_Ribbon = ribbon_point(_Points, CG_VERTEX_ID);
    v.position = cg_Ribbon.local;
    v.normal = cg_Ribbon.frame.normal;
    v.color = vec4(1.0);
    v.uv0 = cg_Ribbon.uv;
    v.uv1 = v.uv0;
    v.uv2 = v.uv0;
    v.uv3 = v.uv0;
    v.world = cg_Ribbon.world;
}
```

| Block | Says |
|---|---|
| `Factory { }` | `VertexType` (required: `spatial` reads a mesh, `none` reads nothing); `PlacesNothing On` if a material must place each vertex itself; `Parameters <name>` |
| `EngineBuffers { }` | engine buffer tokens its draws bind (`CgEngineBufferRegistry`) |
| `Tags { }`, `Properties { }` | added to every material it draws |
| `Keywords { }` | its own switches, as a material's, declared for every material drawn by it (`Sequence`'s `FX_ON_MESH_PARTICLES`, so they serve the mesh particles usage) |
| `Declarations { }` | GLSL at file scope in both stages: includes, the parameters' variable |
| `Parameters { }` | each field a fragment may read: `type name;`, arrays as `vec4 d[4];` |
| `fill(inout CgVertex v)` | runs in the vertex stage with the vertex format's attributes (`cg_Position`, …) in scope |
| `defaults(inout CgSurface s)` | optional, runs in the pixel stage before the material's `surface()`: what it starts from (a particle's own light) |
| `Factory { Fade <expression> }` | optional: fades all the material adds by that value after `surface()`, by its blend mode (an ending's fade: `cg_Vfx.presence`), so no material applies it |
| `Factory { Usage <KEYWORD> }` | optional: the keyword its draws compile a material under (`FX_ON_TUBE`), what a material listing it beside another branches on |

A factory has a pixel side as Unreal's does: `defaults` and `Fade` are where a draw kind's convention lives once, rather
than in every material drawn by it.

**A factory describes what is drawn; something must still draw it.** The file says what the material reads; a renderer
of yours (Java) records the draws that bind those buffers and properties. A factory with no renderer behind it
compiles, and nothing ever draws it.

**One material, several factories.** A material drawn by more than one lists them, the first the base; it is compiled
for each, and its code tells them apart by the others' `Usage` keywords, as Unreal compiles a material once per vertex
factory it is used with:

```glsl
Material { BlendMode AlphaComposite  VertexFactory VfxMesh, Sequence }
void vertex(inout CgVertex v, out v2f o) {
#ifdef FX_ON_SEQUENCE
    o.opacity = FX_SEQUENCE_OPACITY;   // only a sequence's node has one
#else
    o.opacity = 1.0;
#endif
}
```

Factories listed together share a vertex type, and at most one has parameters: read them only under its keyword.

A factory built at run time can be registered instead of written as a file
(`CgVertexFactory.register(CgVertexFactory.builder("Ribbons")...build())`); a registration is found before a file.

---

## 6. Examples (surface)

```glsl
// An opaque mesh, lit
#domain surface
Properties { _Albedo ("Albedo", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) { s.baseColor = texture(_Albedo, i.uv0).rgb; }
```

```glsl
// Leaves: masked, both faces
#domain surface
Material { BlendMode Masked  Faces Both }
Properties { _Leaf ("Leaf", sampler2D) = "white" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    vec4 t = texture(_Leaf, i.uv0);
    s.baseColor = t.rgb;
    s.opacityMask = t.a;
}
```

```glsl
// Sparks that bloom: glow only, no colour draw
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory Sprites }
void surface(in v2f i, inout CgSurface s) { s.emissive = cg_Particle.color.rgb * 6.0; }
```

```glsl
// A heat haze: one distortion draw, nothing else
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit }
Properties { _Ripple ("Ripple", sampler2D) = "black" }
struct v2f { vec2 uv0; };
void surface(in v2f i, inout CgSurface s) {
    s.refraction = (texture(_Ripple, i.uv0 + CG_TIME * 0.1).rg - 0.5) * 0.02;
}
```

---

## 7. Post materials

A post material draws **over the whole screen** at a point in the frame: a grade, a fog, a filter. You write the pixel;
the engine draws the screen-covering triangle, holds depth off and blends nothing.

```glsl
// mymod:shaders/post/desaturate.shader
#domain post
Material { Location AFTER_WORLD }
Properties { _Amount ("How grey", float, range(0, 1)) = 1 }
void surface(in CgScreen i, inout CgPostSurface s) {
    vec3 c = s.color;
    s.color = mix(c, vec3(dot(c, vec3(0.2126, 0.7152, 0.0722))), _Amount * POST_WEIGHT);
}
```

```java
CgPostMaterial grey = CgPostMaterial.of("mymod:shaders/post/desaturate.shader");   // at its Location
CgRenderStage.Registration on = CgPostStack.get().add(grey);
grey.weight(0.5f);                       // POST_WEIGHT in the shader; 0 draws nothing
on.close();                              // removes it
```

| `CgScreen` | Is |
|---|---|
| `uv` | 0..1 across the screen, from the bottom left |
| `pixel` | the pixel, `gl_FragCoord.xy` |

| `CgPostSurface` | Starts as | Is |
|---|---|---|
| `color` | the scene's colour, where `surface()` reads it before writing | what the pixel becomes |
| `opacity` | `1` | how much of `color` replaces the scene: naming it mixes over the scene |

**`Location`** is where in the frame it draws (`CgPostPoint`); `CgPostMaterial.of(path, point)` puts it somewhere else.

| `Location` | Draws onto |
|---|---|
| `BEFORE_TRANSLUCENCY` | the lit solid world, before anything see-through: reads custom depth, normals and custom data |
| `BEFORE_SEPARATE_TRANSLUCENCY` | the world before the separate translucency is laid over it |
| `AFTER_WORLD` | the finished world, linear HDR, before the engine's own looks |
| `AFTER_COMPOSITE` | the final picture, in the screen's encoding: an outline, a CRT filter |

`Material { }` takes `Location`, `SceneColorMargin` and `ColorSpace`. `SceneColorMargin` is how far past its own pixel
it samples the scene, 0.001 (its own pixel) by default; a blur reading its neighbours sets more. It reads the scene by
naming it: `CG_SCENE_COLOR(uv)`, `CG_SCENE_EYE_DEPTH(uv)`, `CG_CUSTOM_STENCIL`, `CG_CUSTOM_DATA`,
`CG_SCENE_WORLD_NORMAL`.

**It works in linear light**, as Unreal's do: `s.color` starts as the scene decoded, and what it writes is encoded back
where the screen is stored sRGB. Reading the scene anywhere but its own pixel, decode it the same way:
`cg_PostDecode(CG_SCENE_COLOR(uv).rgb)`. A step into its own `Target` keeps linear.

`ColorSpace Target` works on the values the screen stores instead, nothing decoded or encoded: an effect on the
picture as shown, such as a negative.

```glsl
#domain post
Material { Location AFTER_COMPOSITE  ColorSpace Target }
void surface(in CgScreen i, inout CgPostSurface s) { s.color = mix(s.color, 1.0 - s.color, POST_WEIGHT); }
```

### 7.1 Several steps

A blur, a glow, anything that works at a smaller size first, is a chain of **steps**. Each step but the last draws into
a texture of its own; each later step reads it; the last draws onto the screen.

```glsl
// soft_glow.shader: the bright parts, blurred at half size, added back
#domain post
Material { Location AFTER_WORLD }
Properties { _Threshold ("Brightness that glows", float) = 1  _Strength ("Glow", float) = 0.5 }

Step bright {
    Target RGBA16_FLOAT  Scale 0.5                   // its own texture: the format, half the screen's size
    void surface(in CgScreen i, inout CgPostSurface s) {
        s.color = max(s.color - _Threshold, 0.0);
    }
}

Step blur {
    Target RGBA16_FLOAT  Scale 0.5
    void surface(in CgScreen i, inout CgPostSurface s) {
        vec2 texel = 1.0 / vec2(CG_STEP_SIZE(bright));     // its size, in texels
        s.color = (CG_STEP(bright, i.uv + vec2(texel.x, 0.0)).rgb + CG_STEP(bright, i.uv - vec2(texel.x, 0.0)).rgb) * 0.5;
    }
}

Step final {                                         // the last: onto the screen
    void surface(in CgScreen i, inout CgPostSurface s) {
        s.color += CG_STEP(blur, i.uv).rgb * _Strength;
    }
}
```

- Steps run in the order written. `CG_STEP(name, uv)` samples an earlier step; `CG_STEP_SIZE(name)` is its size.
- Every step but the last needs a `Target` (a `CgFormat`: `RGBA16_FLOAT`, `RG11B10_UFLOAT`, `RGBA8_UNORM`, …) and may
  set a `Scale` (its share of the screen, above 0 and at most 1).
- Helper functions outside the steps are shared by all of them.

---

## 8. UI materials

### 8.1 What a ui material is

A ui material colours **an element on a canvas** (`docs/CANVAS.md`): a box, a stroke, a piece of text. You say what
colour the element is at a pixel; the engine does everything every UI element needs and every hand-written UI shader
used to repeat: the clip (an element inside a scrolled panel stops at its edge), the item's opacity (a fading window),
the antialiased edge, and premultiplying for the blend.

```glsl
// mymod:shaders/ui/pill.shader: a rounded bar with a gradient
#domain ui
#include "crystalgraphics:shaders/lib/sdf.glsl"
Properties { _Top ("Top", color) = (1, 1, 1, 1)  _Bottom ("Bottom", color) = (0.8, 0.8, 0.8, 1) }
void surface(in CgUiInput i, inout CgUiSurface s) {
    float d = sdf_rounded_box(i.local - i.size * 0.5, i.size * 0.5, vec4(min(i.size.x, i.size.y) * 0.5),
            vec4(min(i.size.x, i.size.y) * 0.5));
    s.color = mix(_Top, _Bottom, i.param.y) * i.color;
    s.coverage = sdf_coverage(d, 1.0);               // its own edge, instead of the quad's
}
```

```java
CgMaterial pill = CgMaterial.load("mymod:shaders/ui/pill.shader");
canvas.quad(pill).at(x, y).size(120, 16).color(0xFF40A0FF).submit();
```

| `CgUiInput` | Is |
|---|---|
| `uv` | the element's texture coordinates: a quad's uv rect; a curve's `(t, 0)` along it |
| `param` | 0..1 across the element (a little past either end on a rotated quad's soft edge) |
| `local`, `size` | the pixel's place inside the element and the element's size, in the canvas's units |
| `color` | the element's colour: a quad's `.color(...)`, a curve's along its gradient, a text layer's paint |
| `custom0`, `custom1` | the quad's free values, `.custom0(...)`/`.custom1(...)` |

| `CgUiSurface` | Starts as | Is |
|---|---|---|
| `color` | `i.color` | the colour, straight alpha: the engine premultiplies it |
| `coverage` | the element's antialiased edge | how much of the pixel it covers, 0..1 |
| `premultiplied` | `false` | set it when `color` is premultiplied already (a texture drawn at screen resolution) |

### 8.2 Primitives: which element it colours

A **primitive** is the kind of canvas element a ui material is drawn on: the ui domain's vertex factory. It is more
than where the vertices come from, because each kind of element also finishes the pixel its own way: a box's edge is
its quad's border, a stroke's coverage comes from its curve, a glyph's from its distance field. So a primitive wraps
your `surface()` on both sides:

```
the element's record ─▶ primitive's vertex ─▶ primitive fills CgUiInput ─▶ your surface() ─▶ primitive finishes:
                                                                                              coverage, clip, opacity,
                                                                                              premultiply
```

```glsl
Material { Primitive Text }          // Quad (default) | Curve | Text | mymod:Ring
```

| `Primitive` | Drawn with | Before `surface()` | After it |
|---|---|---|---|
| `Quad` (default) | `canvas.quad(material)`: boxes, images, bars | `uv`, `param`, `size`, `color`, the custom values; `coverage` the quad's antialiased edge; `cg_UiTexture(tex, i)` | clip, opacity, the blend's write |
| `Curve` | `canvas.curve(...)`, `triangle`, `cell`: lines, Bézier strokes, fills | `color` along the stroke, `uv.x` = `t` along it, `local` the point; `coverage` the stroke's | drops what is invisible, then clip, opacity, the blend's write |
| `Text` | a text style's layer (`CgTextMaterials`) | `color` the paint, its gradient applied | the glyph's coverage (bitmap or MSDF), its gamma, premultiply |

```glsl
// Text that shimmers: the paint only; the glyph's shape is the engine's
#domain ui
Material { Primitive Text }
void surface(in CgUiInput i, inout CgUiSurface s) {
    s.color.rgb *= 0.8 + 0.2 * sin(i.param.x * 12.0 + CG_TIME * 4.0);
}
```

**Drawn under any blend.** A ui material draws `PREMULTIPLIED` as loaded (`CgMaterial.load(path)`); drawn under another
(`CgMaterial.load(path, CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build())`), the primitive writes what
that blend wants, as Unreal's simple element shader does: straight alpha under `ALPHA`, weighed by its alpha under
`ADDITIVE` and `SUBTRACT`, toward white where it is transparent under `MODULATE`. Your `surface()` is the same for all.

**Its colour is sRGB**, as the canvas's is: drawn into a linear scene the engine decodes it. `Material { ColorSpace
Target }` says the material converts itself, as glass does over a backdrop already in the target's encoding.

**`cg_UiTexture(tex, i)`** samples a texture at `i.uv` on a `Quad`, filtered across texels while the quad is rotated, as
an image must be or its texels shimmer; `cg_UiTexture(tex, i, layer)` reads a 2D array's layer.

```glsl
// An image tinted by the quad's colour, under whichever blend it is drawn
#domain ui
Properties { _MainTex ("Image", sampler2D) = "white" }
void surface(in CgUiInput i, inout CgUiSurface s) { s.color = cg_UiTexture(_MainTex, i) * i.color; }
```

### 8.3 Writing your own primitive

A new kind of canvas element is a file, **`assets/mymod/shaders/canvas/primitives/Ring.primitive`**, read when a material
says `Primitive mymod:Ring`. It is real GLSL: a `v2f`, a `vertex()`, and a `fragment()` that fills a `CgUiInput`
and a `CgUiSurface`, runs the material at **`CG_SURFACE(input, s)`**, then writes `fragColor`. The engine's three
(`crystalgraphics:shaders/canvas/primitives/Quad.primitive`, `Curve`, `Text`) are the examples to start from.

```glsl
// assets/mymod/shaders/canvas/primitives/Ring.primitive
Primitive { Queue Transparent }
EngineBuffers { quad clip }                         // what its elements are recorded into
Keywords { THICK ("A wider band") }                 // its own switches, as a material's (optional)
Declarations { #include "mymod:shaders/ring.glsl" }   // GLSL every material on it gets
Properties { }                                      // values every material on it gets (optional)

RenderState {
    Blend ONE ONE_MINUS_SRC_ALPHA                   // premultiplied, as a ui material is loaded
    Cull OFF
    DepthTest LEQUAL
    DepthWrite OFF
}

struct v2f { vec2 _cg_in_param; };                  // its own fields: the _cg_in_ prefix keeps them apart from a material's

void vertex(out v2f o) {
    o._cg_in_param = CG_QUAD_EDGE_PARAM;
    gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o._cg_in_param), 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    float clip = CG_CLIP_QUAD_COVERAGE;             // before the material: it takes derivatives
    CgUiInput ui;
    ui.param = i._cg_in_param;
    ui.uv = ui.param;
    ui.size = vec2(length(CG_QUAD_RIGHT), length(CG_QUAD_UP));
    ui.local = ui.param * ui.size;
    ui.color = CG_QUAD_COLOR;
    ui.custom0 = CG_QUAD_CUSTOM0;
    ui.custom1 = CG_QUAD_CUSTOM1;
    CgUiSurface s;
    s.color = ui.color;
    s.coverage = ring_coverage(ui.param);           // what makes it a ring
    s.premultiplied = false;
    CG_SURFACE(ui, s);                              // the material runs here
    fragColor = cg_UiWrite(s, s.coverage * clip * CG_QUAD_OPACITY);   // as the draw's blend wants it
}
```

As with a factory, the file is the shader side; the canvas side (recording elements into the buffers it reads) is
Java. A primitive over the canvas's own quads, like this ring, needs none: `canvas.quad(ringMaterial)` draws it.

---

## 9. `global`: the engine's own

For the renderer's own passes and a consumer's own mesh pass: a raw vertex and fragment pair, no outputs, nothing
applied for you. The one domain that writes `gl_Position` and a `RenderState`, and names the engine buffers it reads
(`EngineBuffers { curve clip }`), since nothing else draws it.

`Material { }` takes:

| Setting | Values | Does |
|---|---|---|
| `ColorSpace` | `Target` (default), `sRGB`, `Linear` | `Target`: its colour is written as it means it; `sRGB` is decoded into a linear scene, `Linear` encoded onto an encoded target, as a surface material's |
| `Queue` | `Overlay` (default), `Background`, `Geometry`, `AlphaTest`, `Transparent`, `AfterDistortion` | where a mesh pass of yours sorts among the world's draws |
| `Media` | `Off` (default), `On` | whether the smoke and gas in front of it dim it, as a translucent surface material's |
| `SceneColorMargin` | a number | how far past its pixel it samples the scene |

```glsl
#domain global
RenderState { Blend OFF  DepthTest ALWAYS  DepthWrite OFF  Cull OFF }
Properties { _Source ("Source", sampler2D) = "black" }
struct v2f { vec2 uv; };
void vertex(out v2f o) {
    o.uv = post_fullscreen_uv(CG_VERTEX_ID);
    gl_Position = vec4(o.uv * 2.0 - 1.0, 0.0, 1.0);
}
void fragment(in v2f i, out vec4 color) { color = texture(_Source, i.uv); }
```

Write a `surface`, `post` or `ui` material instead wherever one fits.

---

## 10. What the engine works out for you

| You | The engine |
|---|---|
| write `emissive`, `refraction`, … | draws the passes those need, and none it does not |
| name `CG_SCENE_COLOR`, `CG_SCENE_EYE_DEPTH`, … | copies or binds the scene for you |
| name `CG_GLOBAL_skill_darken` | binds the global parameters |
| name `cg_Particle.relativeTime` | passes that field, and only it, to the pixel |
| set `VertexFactory`, `Primitive` | binds what that factory or primitive reads |
| set `PerDrawValues On` | reads each property from the draw's row where its renderer gives one |
| write `#ifdef` on a keyword | compiles that combination once, the first time it is asked for |

---

## Reference

### Things that fail, and why

| You wrote | It says | Because |
|---|---|---|
| `#pragma ...` | `Keywords { }`, `PerDrawValues On`, or a name | every pragma has a named home |
| `gl_Position` in a surface | move `v.world` | every pass projects the same `v.world` |
| `s.opacity` under `Opaque` | a translucent blend mode | an opaque material hides everything behind it |
| `s.opacityMask` without `Masked`, `ClipDepth` or `DepthPrepass` | one of those | nothing would cut |
| a `v2f` field with no `vertex()` and no auto name | write `vertex()`, or rename it | nothing would fill it |
| `cg_Tube` under `VertexFactory Sprites` | the factories that have it (an undeclared name, where none has been read yet) | a sprite has no tube |
| `_FxPath` declared under a tube factory | remove it | the tube's draw binds it |
| `VertexFactory mymod:X` with no file | the path it looked for | factories are found by name |
| `Location BEFORE_COMPOSITE` | another location | that point feeds the engine's composite |

### Where things live

| What | Where |
|---|---|
| The engine's factories | `crystalgraphics:shaders/factories/*.factory` |
| The engine's ui primitives | `crystalgraphics:shaders/canvas/primitives/*.primitive` |
| The structs `vertex()` and `surface()` take | `crystalgraphics:shaders/env/material/surface.glsl` |
| How a format file becomes passes (engine side) | `material/parse/CgMaterialFormat`, `CgFormatTemplates` |
| The older `Pass { }` format, still read for unconverted shaders | [`SHADERS.md`](SHADERS.md) |
| Precaching a material you draw | [`PRECACHE.md`](PRECACHE.md) |
