# Materials

A material is a `.shader` ([`../SHADERS.md`](../SHADERS.md)). This page is about what a material drawn on particles
reads: the particle and the vertex it is drawing, as Unreal's material graph reads them. It grows as the engine's
material work lands.

## A material, as a file and in Java

A `material` asset names its `.shader` (its archetype) and sets what Unreal's material instance sets: its blend and
faces, its keywords (static switches), its values and its textures. A child names a parent and overrides some of them.

```yaml
# assets/mymod/materials/fx/sparks.yaml
_v: 1
shader: crystalgraphics:shaders/vfx/particle/sprite.shader
state: { BlendMode: Additive, Faces: Both }
values: { _Color: [2.0, 1.0, 0.5, 1.0], _SoftDistance: 0.25 }
textures:
  _Sheet: { texture: mymod:textures/fx/sparks.png, sampler: { filter: bilinear, wrap: clamp } }
```

```java
CgMaterialDefinition sparks = CgMaterialDefinition.of("mymod:materials/fx/sparks",
                "crystalgraphics:shaders/vfx/particle/sprite.shader")
        .blend(CgBlendMode.ADDITIVE).twoSided()
        .color("_Color", 0xFFFF8040, 2f)              // 0xAARRGGBB, its rgb times 2
        .value("_SoftDistance", 0.25f)
        .texture("_Sheet", "mymod:textures/fx/sparks.png", CgSampler.Filter.BILINEAR, CgSampler.Address.CLAMP)
        .build();                                     // the same definition the file loads
```

**Drawing it.** A definition draws as one `CgMaterial`, made once and shared:

```java
CgMaterial m = CgMaterial.of(sparks);
CgWorldRenderer.get().draw(mesh, m).at(x, y, z).submit();
```

**A variant without a file.** `derive` is a child made in code: the same overrides on the same parent answer the same
definition, and one changing only values shares its parent's programs.

```java
CgMaterialDefinition soft = CgMaterialDefinitions.derive(sparks, Map.of("keywords", List.of("SOFT")));
CgMaterialDefinition slow = CgMaterialDefinitions.derive(sparks, Map.of("values", Map.of("_SoftDistance", 1.0)));
```

- A name the shader does not declare fails at `build()`, or at load for a file, naming the material and the property.
- A texture is a 2D image, its path the image's (`<namespace>:textures/....png`).

### `state`: how it draws, over its shader's

`state` sets some of its shader's `Material { }` properties ([`../SHADERS.md`](../SHADERS.md)), by the same names and
values, over what the shader says. Leave one out and the shader's own holds, as an Unreal instance keeps its parent's
blend mode unless it overrides it. A child's `state` merges over its parent's.

| Property | Values | In Java | What it does |
|---|---|---|---|
| `BlendMode` | `Opaque`, `Masked`, `Translucent`, `AlphaComposite`, `Additive`, `Modulate`, `Subtract` | `.blend(CgBlendMode)`, `.masked(clip)` | How it covers what is behind it; `AlphaComposite` is premultiplied |
| `MaskClip` | a number, 0.5 by default | `.masked(clip)` | Where a `Masked` material's `opacityMask` stops being a hole; the shader's `_Clip` receives it |
| `ShadingModel` | `WorldLit`, `Unlit`, `Subsurface`, or a kit's model by id (`kakarot:cell_tone`) | `.shading(CgShadingModel)` | How light reaches it |
| `Faces` | `Front`, `Back`, `Both` | `.twoSided()`, `.cull(CgCullState)` | Which faces draw: `Both` is two-sided, `Back` only a volume's far wall |
| `DepthTest` | `true`, `false` | `.depthTest(false)` | `false` draws it over whatever is in front (below) |
| `DepthWrite` | `true`, `false` | `.depthWrite(true)` | Whether it writes depth, over its blend's own |
| `TranslucencyPass` | `Separate` | `.separateTranslucency()` | Drawn after every other translucent thing (below) |

- **The rest of `Material { }` is the shader's**: `Fog`, `Media`, `DepthPrepass`, `ClipDepth`, `VertexFactory` and the
  others change what the program is, so a different look is a different shader. Naming one in `state` fails at load,
  naming the material.
- **A state the shader forbids fails at load**, naming the material: `DepthTest: false` on an opaque or masked blend, a
  `DepthWrite` where the shader's `DepthPrepass` or `ClipDepth` owns depth.
- **`DepthTest` and `DepthWrite` are `true` or `false`**, where a shader writes `On` or `Off`: a file holds the
  spelling its writer writes. `DepthTest Strict` and the other translucency passes are the shader's only.
- **Its programs are shared where its state allows**: `BlendMode`, `ShadingModel` and `DepthTest` make a material
  programs of its own; `Faces`, `MaskClip`, `DepthWrite` and `TranslucencyPass` are pipeline state, so an instance
  changing only those draws with its parent's programs.

**`DepthTest: false`** (Unreal's `bDisableDepthTest`) draws it over whatever is in front: a hit spark the floor would
otherwise cut. Its glow is not hidden by the scene either. Translucent blends only. It then shows through walls too, so
give its particles `crystalgraphics:occlusion` ([particles](particles.md)) to fade them out where something covers
them. **Give it no depth fade** (`CG_DEPTH_FADE`, soft particles): behind the floor the scene is nearer than the
fragment, so the fade is 0 there at any distance, 0 included, and cuts the draw on the very line the depth test would
have.

```yaml
state: { BlendMode: Additive, DepthTest: false }
```

**`TranslucencyPass: Separate`** (Unreal's `bEnableSeparateTranslucency`) draws a translucent material after every
other translucent draw, unbent by their distortion and still tested against the scene's depth: it sorts in
`CgRenderQueue.AFTER_DISTORTION` and up, in `CgSortLayer.SEPARATE_TRANSLUCENCY` or a later layer. Under the HDR scene
it draws into a target of its own, laid over the scene after the colour regions, as Unreal composites it after its
`PrePostProcessPass`: a `colour_region` never grades it. A blend that multiplies stays in the scene.

## A particle's material: the vertex factory

A material on particles never places its own vertices. Its vertex factory does, for the kind of draw it is, and hands
the material what it placed: Unreal's vertex factories, and its material parameters (`FMaterialVertexParameters`,
`FMaterialParticleParameters`). The material reads the vertex as `v` and the particle as `cg_Particle`, and changes
what it wants; the engine projects `v.world` after.

```glsl
#domain surface
// a sprite: a quad a particle, placed by the engine; every number readable per draw
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Mask ("Mask", sampler2D) = "white"
}

struct v2f { vec2 uv0; };                         // filled from the placed vertex by name

void surface(in v2f i, inout CgSurface s) {
    float a = texture(_Mask, i.uv0).r * cg_Particle.color.a;   // naming a field passes it to the pixel
    s.baseColor = cg_Particle.color.rgb * a;
}
```

The same material on a mesh drawn for every particle is on `MeshParticles`: the fields are the same, and what they
answer is the mesh's.

```glsl
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory MeshParticles }
struct v2f { vec2 uv0; vec4 colour; };
void vertex(inout CgVertex v, out v2f o) {
    v.world += v.normal * 0.05 * cg_Particle.relativeTime;   // a world position offset: it swells as it ages
    o.uv0 = v.uv0;
    o.colour = cg_Particle.color * v.color;                   // the particle's colour times the mesh's
}
```

| `CgVertex` | `Sprites` | `MeshParticles` |
|---|---|---|
| `world` | the corner, camera-relative, in blocks | the mesh's vertex through the particle's place, turn and size |
| `position` | the corner's offset from the particle's centre | the mesh's own vertex |
| `uv0` … `uv3` | frame a's and frame b's of its flipbook, then both again | the mesh's two UV sets, then zero |
| `normal` | the normal facing the eye (`cg_SpriteAxes`: right, up and normal) | the mesh's normal (`cg_TangentToWorld`: the whole frame) |
| `color` | the particle's colour | the mesh's own vertex colour |

| `CgMaterialParticle` | Is |
|---|---|
| `color` | its colour, linear; alpha its opacity |
| `relativeTime`, `random` | how far through its life (0..1); a number fixed for its life (0..1) |
| `velocity` | xyz its direction, w its speed in blocks a second |
| `positionAndSize` | its centre, camera-relative; w its radius on a sprite, 1 on a mesh |
| `size`, `spriteRotation` | a sprite's width and height and its turn in radians; 0 on a mesh |
| `subUVCoords0`, `subUVCoords1`, `subUVLerp` | its flipbook's two frames and the blend between them |
| `record` | its record: `CG_PARTICLE_HEAT(cg_Particle.record)` and the rest of the `particle` buffer's macros read what these fields do not |

- `cg_dynamic_parameter(cg_Particle, i, fallback)` is the particle's dynamic parameter `i` (0 to 3), set by its emitter's
  `dynamic_material_parameters` ([particles](particles.md) § *Values a particle carries for its material*), and
  `fallback` where its emitter sets none: Unreal's DynamicParameter node and its defaults. Named in `surface()`, the fields
  it reads are passed to the pixel for it. A renderer with `dynamic_parameter_index: first` (Cascade's
  factories; `dynamicParameterIndex(CgVfxDynamicParameterIndex.FIRST)` in Java) reads parameter 0 at every index, and
  (1, 1, 1, 1) where its emitter sets none, whatever the fallback; on a sprite renderer of a `spawning: cascade_gpu`
  emitter every index reads (0, 0, 0, 0), as Cascade's GPU sprites carry none:

  ```yaml
  renderers:
    - { id: r1, kind: mesh, material: mymod:materials/pulse, dynamic_parameter_index: first }
  ```
- A sprite's facing, stretch, camera offset (blocks toward the eye) and flipbook are its renderer's (`_cg_Facing`,
  `_cg_CameraOffset`, `_cg_SubImage`, ...), or its particle's where its emitter sets them per particle: never declare a
  `_cg_` property in a material.
- **The system's bounds**: `CG_OBJECT_POSITION` (their centre, camera-relative) and `CG_OBJECT_RADIUS` (their
  sphere's radius, blocks), Unreal's object position and radius, in either stage. They are the system's fixed bounds,
  its file's `fixed_bounds` or `withFixedBounds` in Java; a system drawing a material that reads them with none fails
  to load, naming it.

  ```glsl
  float d = length(cg_FragmentPosition()) - length(CG_OBJECT_POSITION);   // camera-relative: the eye is at 0
  float ring = clamp(0.5 - abs(d) / CG_OBJECT_RADIUS, 0.0, 1.0);
  ```

  ```java
  static final CgVfxSystemDefinition ORB = CgVfxSystemDefinition.of("mymod:orb", ORB_LOOK)
          .withFixedBounds(new CgVfxSystemDefinition.FixedBounds(-4, -4, -4, 4, 4, 4));
  ```
- **Local to world**: `CG_PARTICLE_LOCAL_TO_WORLD`, a `mat3` in the vertex stage, Unreal's `TransformVector` from
  local to world as each factory means it: a sprite's and a ribbon's system's turn, a mesh particle's own instance
  matrix (its turn and size). Not `mat3(CG_OBJECT_TO_WORLD)`, which on a sprite or ribbon draw holds the draw's place
  alone.

  ```glsl
  v.world += CG_PARTICLE_LOCAL_TO_WORLD * (offset.xzy * 0.01);   // an Unreal local offset, in centimetres
  ```
- **Macro UVs**: `CG_PARTICLE_MACRO_UV`, Unreal's ParticleMacroUV parameters for this view, in either stage: the
  system's macro UV centre in NDC (y up) and the scales taking its radius on screen to one UV, worked out as Cascade does
  (`ParticleSystemRender.cpp`, `GetObjectPositionAndScale`). The centre and radius are the system's `macro_uv`, or the
  renderer's ([assets](assets.md) § *Macro UVs*). A texture laid over the whole effect on screen:

  ```glsl
  vec4 macro = CG_PARTICLE_MACRO_UV;
  vec2 uv = (gl_FragCoord.xy / CG_RESOLUTION * 2.0 - 1.0 - macro.xy) * macro.zw + 0.5;
  ```

  Its scales are 0 where the radius is not positive or its extent on screen is 0 or not finite: every pixel then reads
  the centre.

### Emissive and opacity

A material ported from Unreal writes what Unreal's material outputs, its emissive colour and its opacity, and the
engine composes them for the blend the material draws with, as Unreal's base pass does. The same program then draws
under any of a kit's blends.

```glsl
Material { BlendMode Translucent  ShadingModel Unlit  VertexFactory Sprites }
void surface(in v2f i, inout CgSurface s) {
    s.emissive = cg_Particle.color.rgb * _Glow;
    s.opacity = texture(_Mask, i.uv0).r * cg_Particle.color.a;
}
```

### Unreal's expressions

`lib/material/` holds Unreal's expressions as functions a material includes: DepthFade, Panner, Rotator,
ParticleSubUV and RotateAboutAxis ([`../SHADERS.md`](../SHADERS.md) § *Unreal's material expressions*).

```glsl
#include "crystalgraphics:shaders/lib/material/depth_fade.glsl"
#include "crystalgraphics:shaders/lib/material/particle_sub_uv.glsl"

// in vertex(): the flipbook's two frames, read where the particle is
o.colour = CG_PARTICLE_SUB_UV(_Sheet, cg_Particle, true);
// in surface(): faded where it meets the scene, over half a block
float a = CG_DEPTH_FADE(i.colour.a, 0.5, gl_FragCoord.xy / CG_RESOLUTION);
```

### Unreal's Default Lit: `default_lit.glsl`

A solid lit as Unreal's Default Lit shading model does, Lambert and the GGX lobe under the sun, the four nearest point
lights and the sky, for a ported material whose game lit it so (stage debris). It is drawn in the **transparent**
firing as a solid, writing depth: there the deferred apply has run and leaves it alone, the light grid is this
frame's and the sun's shadow map is bound. In the opaque firing it would be lit twice.

```glsl
#include "crystalgraphics:shaders/lib/material/default_lit.glsl"
Material { BlendMode AlphaComposite  DepthWrite On  ShadingModel Unlit  ColorSpace Linear  VertexFactory MeshParticles }
void surface(in v2f i, inout CgSurface s) {
    vec3 orm = texture(_ORM, i.uv0).rgb, lit;   // occlusion, roughness, metallic
    CG_DEFAULT_LIT(lit, texture(_Color, i.uv0).rgb, orm.b, 0.5, orm.g, orm.r, i.normal, i.world);
    s.baseColor = CG_DEFAULT_LIT_OUTPUT(lit);
}
```

- `CG_DEFAULT_LIT(out, baseColor, metallic, specular, roughness, occlusion, n, p)`: linear base colour, Unreal's
  specular (0.5 its default), occlusion of the sky's light alone, world normal and camera-relative position.
- **Set `ColorSpace Linear`** and write `CG_DEFAULT_LIT_OUTPUT`: linear light, which the engine writes as it
  is into the HDR scene and encodes onto the host's own target.
- It does not cast shadows yet: only opaque draws are casters.

### Easy to get wrong

- **Include what you call**: `CG_DEPTH_FADE` with no `depth_fade.glsl` included fails as "no matching overloaded
  function".
- **A helper is plain GLSL at file scope**: `vertex()` and `surface()` both reach it, and each stage keeps only what
  it calls.
- **On `MeshParticles`, `v.color` is the mesh's**: the particle's colour is `cg_Particle.color`, as in Unreal's mesh
  particles.
- **A `VertexFactory` nothing registers** fails to load, naming the file it looked for.
