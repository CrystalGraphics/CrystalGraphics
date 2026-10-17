# Effects: how a layer draws

A look draws through its layers: each a `.shader` on one or more slots (see [`../VFX.md`](../VFX.md) § *Kits, layers
and slots*). This page is how a layer draws: how it blends with what is behind it, which faces it shows, whether it
writes depth, and where it draws among the rest. In Unreal these are a material instance's base property overrides
and a Niagara renderer's sort order.

## A layer's render state: blend, two-sided, depth, clip

A shader authors its own `RenderState`. A layer can draw it under another one, so one shader serves every way a kit
draws it: the same soft sprite added for fire and blended over for smoke.

```java
static final String SOFT = "mymod:shaders/vfx/soft.shader";

static final CgVfxLayer FIRE = CgVfxLayer.of(SOFT).on(FLAMES)
        .state(CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build())
        .build();

static final CgVfxLayer SMOKE = CgVfxLayer.of(SOFT).on(PUFFS)
        .state(CgMaterialState.builder().blend(CgBlendMode.PREMULTIPLIED).build())
        .build();
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
| `SUBTRACT` | `Blend ONE ONE`, `BlendEquation REV_SUB` | (none) | what it takes away from behind it |

A shader drawn under a mode draws exactly as if it had authored that line: its fog, its decode into the HDR scene,
how smoke in front dims it, and whether its glow merges into its own draw all follow the mode. `SUBTRACT` is fogged
as `ADDITIVE` is, and its glow (a codeless Emissive pass) adds rather than subtracts.

### Two-sided and depth

```java
CgMaterialState.builder().twoSided().build();                  // both faces: Unreal's Two Sided
CgMaterialState.builder().cull(CgCullState.FRONT).build();     // the inside of a dome
CgMaterialState.builder().depthWrite(true).build();            // its Forward pass writes depth
```

Two-sided applies to every pass of the material, its glow and its haze included.

### Clipped: solid where it covers

`clip()` is Unreal's Masked: a fragment below the threshold is discarded, the rest writes depth, so what draws later
behind it stays hidden. The threshold is the material's `_Clip` (0.5 where the shader declares none). The shader says
where it is solid with `cg_Clip(value)` ([`../SHADERS.md`](../SHADERS.md) § *Depth from a transparent material*).

```java
static final CgVfxLayer INK = CgVfxLayer.of("mymod:shaders/vfx/ink.shader").on(STROKES)
        .state(CgMaterialState.builder().blend(CgBlendMode.ALPHA).clip().build())
        .set("_Clip", 0.33f)
        .build();
```

### What it costs

- **Each blend mode, and a clip, compiles once**: the shader's programs under that mode, made the first time a layer
  draws with it, and shared by every layer and material asking for it. Warm them with the look (`VFX.prepare(LOOK)`).
- **Cull and depth write compile nothing**: they are the draw's pipeline state, on the same programs.

## Textures and samplers

A texture samples as its spec says (`CgTextureSpec`: its filter and wrap). A layer can sample it another way
instead, so one image is loaded once and used clamped by one layer and tiled by another. Unreal calls this a
material's sampler source.

```java
static final CgTexture2D NOISE = CgTextures.of("mymod:textures/vfx/noise.png");

static final CgVfxLayer MASK = CgVfxLayer.of("mymod:shaders/vfx/mask.shader").on(BALL)
        .texture("_Mask", NOISE, CgSampler.BILINEAR_CLAMP)          // a soft edge that never wraps
        .build();
static final CgVfxLayer SCROLL = CgVfxLayer.of("mymod:shaders/vfx/scroll.shader").on(BALL)
        .texture("_Scroll", NOISE, CgSampler.TRILINEAR_WRAP)         // the same upload, tiled
        .build();
```

A sampler of your own, axis by axis:

```java
CgSampler strip = CgSampler.builder()
        .filter(CgSampler.Filter.TRILINEAR)                           // NEAREST, BILINEAR or TRILINEAR
        .address(CgSampler.Address.WRAP, CgSampler.Address.CLAMP)     // along u, then v (and w)
        .anisotropy(8)                                                // sharper at a slant; 1 is none
        .build();
```

| `CgSampler.Address` | A coordinate past 0 to 1 reads | Unreal |
|---|---|---|
| `WRAP` | the texture again | Wrap |
| `CLAMP` | the edge texel | Clamp |
| `MIRROR` | the texture again, every other copy flipped | Mirror |

- **Without a sampler** a texture samples as its spec says, as before.
- **On a texture with no mips**, `BILINEAR` and `TRILINEAR` sample its base level.
- **Anisotropy is clamped** to what the player's GPU has.

### Cooked textures: BC7, cooked once

An effect kit ships its textures cooked: BC7 with every mip, a quarter of RGBA8's memory, and nothing encoded at load.
Name the `.cgtex` where you would name the PNG:

```java
static final CgTexture2D SMOKE = CgTextures.of("mymod:cooked/fx/smoke.cgtex", CgTextureSpec.RGBA8_LINEAR_REPEAT);
static final CgVfxLayer PUFF = CgVfxLayer.of("mymod:shaders/vfx/smoke.shader").on(BALL)
        .texture("_MainTex", SMOKE, CgSampler.TRILINEAR_WRAP)         // samplers work on it as on any texture
        .build();
```

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

A layer's mesh (`mesh(path)`, a `MESHES` emitter's) is `SPATIAL`: position, normal, vertex colour and two UV sets,
as Unreal's static meshes are. A glTF's `COLOR_0` and `TEXCOORD_1` load into it; where a file has none, colour is white
and the second UV the first. A shader reads them as `cg_Color` and `cg_TexCoord1`:

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
CgVfxLayer.of("mymod:shaders/vfx/layered.shader").on(SLASH).mesh("mymod:models/slash.glb").keyword("SECOND_LAYER")
```

## Where a layer draws: its order and its sort layer

Within its effect, a layer draws by its role (smoke, light, distortion, surface), then by its order hint, then in
the order the look holds it. The hint is Niagara's renderer sort order: lowest first, 0 unless given.

```java
static final CgVfxKit RINGS = CgVfxKit.of("mymod:rings")
        .layer(CgVfxLayer.of(GLOW).on(RING).order(12).build())     // over everything of order 0 to 11
        .layer(CgVfxLayer.of(CORE).on(RING).order(4).build())      // drawn before it, though added after
        .build();
```

A layer can also leave its effect's place among every other translucent draw in the world, by a sort layer: Unreal's
translucency sort priority. A later sort layer draws after an earlier one whatever their distances.

```java
CgVfxLayer.of(FLASH).on(BALL).sortLayer(CgSortLayer.OVERLAY).build()      // over every effect
```

An imported kit's render layers are order hints: a kit converted from another engine keeps its draw order this way.

## Easy to get wrong

- **The mode is not the shader's queue.** A shader in the opaque queue drawn `ADDITIVE` still draws with the opaque
  pass; give a blended shader `Queue = "Transparent"`.
- **The fragment writes for its mode.** A shader whose colour is straight drawn `PREMULTIPLIED` comes out too bright at
  its edges: multiply by the alpha, or draw it `ALPHA`.
- **A depth write and a clip are refused together**, and either on a shader whose `"Depth"` tag already owns its depth
  (`Clip` or `Prepass`): the shader fails to load, the log naming the state.
- **An order hint orders within a role**: a `SURFACE` layer of order 0 still draws after a `SMOKE` layer of order 200.
- **An order hint is 0 to 255**; `order(n)` throws for another.
