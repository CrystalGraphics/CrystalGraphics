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
state: { blend: additive, two_sided: true }
values: { _Color: [2.0, 1.0, 0.5, 1.0], _SoftDistance: 0.25 }
textures:
  _Sheet: { texture: mymod:textures/fx/sparks.png, sampler: { filter: bilinear, wrap: clamp } }
```

```java
CgMaterialDefinition sparks = CgMaterialDefinition.builder("mymod:materials/fx/sparks",
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
- `depth_test: false` fails when it draws: no material state carries it yet.

## A particle's material: the vertex factory

A material on particles never places its own vertices. The engine does, for the kind of draw it is, and hands the
material what it placed: Unreal's vertex factories, and its material parameters (`FMaterialVertexParameters`,
`FMaterialParticleParameters`). The material reads them through two calls and changes what it wants before projecting.

```glsl
#type none                         // a sprite: a quad a particle, placed by the engine
#pragma cg_use particle
#pragma cg_use rows

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Mask ("Mask", sampler2D) = "white"
}

struct v2f { vec2 uv; vec4 colour; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthWrite OFF
    }
    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();       // where the engine put this corner
        CgMaterialParticle p = cg_material_particle();   // the particle it belongs to
        o.uv = v.texCoords[0];
        o.colour = p.color;
        gl_Position = cg_material_project(v);
    }
    void fragment(in v2f i, out vec4 fragColor) {
        float a = texture(_Mask, i.uv).r * i.colour.a;
        fragColor = vec4(i.colour.rgb * a, 0.0);
    }
}
```

The same material on a mesh drawn for every particle is `#type spatial`: the calls are the same, and what they answer
is the mesh's.

```glsl
#type spatial                      // a mesh a particle: the emitter's renderer is MESHES
#pragma cg_use particle

void vertex(out v2f o) {
    CgMaterialVertex v = cg_material_vertex();
    CgMaterialParticle p = cg_material_particle();
    v.worldPosition += v.tangentToWorld[2] * 0.05 * p.relativeTime;   // a world position offset: it swells as it ages
    o.uv = v.texCoords[0];
    o.colour = p.color * v.vertexColor;                               // the particle's colour times the mesh's
    gl_Position = cg_material_project(v);
}
```

| `CgMaterialVertex` | Sprite (`#type none`) | Mesh (`#type spatial`) |
|---|---|---|
| `worldPosition` | the corner, camera-relative, in blocks | the mesh's vertex through the particle's place, turn and size |
| `texCoords[4]` | frame a's and frame b's of its flipbook, then both again | the mesh's two UV sets, then zero |
| `tangentToWorld` | right, up, and the normal facing the eye | a frame about the mesh's normal |
| `vertexColor` | the particle's colour | the mesh's own vertex colour |

| `CgMaterialParticle` | Is |
|---|---|
| `color` | its colour, linear; alpha its opacity |
| `relativeTime`, `random` | how far through its life (0..1); a number fixed for its life (0..1) |
| `velocity` | xyz its direction, w its speed in blocks a second |
| `positionAndSize` | its centre, camera-relative; w its radius on a sprite, 1 on a mesh |
| `size`, `spriteRotation` | a sprite's width and height and its turn in radians; 0 on a mesh |
| `subUVCoords0`, `subUVCoords1`, `subUVLerp` | its flipbook's two frames and the blend between them |
| `record` | its record: `CG_PARTICLE_HEAT(p.record)` and the rest of `cg_use particle`'s macros read what these fields do not |

- `cg_material_project(v)` is the clip position of `v.worldPosition`: call it last, after any offset.
- `cg_dynamic_parameter(p, i, fallback)` is the renderer's dynamic parameter `i`, and `fallback` where it binds none.
- A sprite's facing, stretch, camera offset and flipbook are its renderer's (`_cg_Facing`, `_cg_SubImage`, ...): never
  declare a `_cg_` property in a material.

### Emissive and opacity: `"Output" = "EmissiveOpacity"`

A material ported from Unreal writes what Unreal's material outputs: its emissive colour and its opacity. Tagged
`"Output" = "EmissiveOpacity"`, it writes them as they are, and the engine composes them for the blend the material
draws with, as Unreal's base pass does. The same program then draws under any of a kit's blends.

```glsl
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Output" = "EmissiveOpacity" }

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = vec4(i.colour.rgb * _Glow, texture(_Mask, i.uv).r * i.colour.a);   // emissive, opacity
}
```

| Blend | Drawn as |
|---|---|
| `ADDITIVE`, `SUBTRACT` | emissive times opacity, added |
| `ALPHA`, `PREMULTIPLIED`, `MODULATE`, `OPAQUE` | as written: the blend and the fog take the rest |

### Unreal's expressions

`lib/material/` holds Unreal's expressions as functions a material includes: DepthFade, Panner, Rotator,
ParticleSubUV and RotateAboutAxis ([`../SHADERS.md`](../SHADERS.md) § *Unreal's material expressions*).

```glsl
#include "crystalgraphics:shaders/lib/material/depth_fade.glsl"
#include "crystalgraphics:shaders/lib/material/particle_sub_uv.glsl"

// in the vertex function: the flipbook's two frames, read where the particle is
o.colour = CG_PARTICLE_SUB_UV(_Sheet, p, true);
// in the fragment function: faded where it meets the scene, over half a block
float a = CG_DEPTH_FADE(i.colour.a, 0.5, gl_FragCoord.xy / CG_RESOLUTION);
```

### Easy to get wrong

- **Include what you call**: `CG_DEPTH_FADE` with no `depth_fade.glsl` included fails as "no matching overloaded
  function".
- **Every function is declared in a Pass**, before its `vertex()`. Code between `struct v2f` and the first `Pass` is
  refused, naming it. A Distortion pass, or a codeless Emissive pass, takes the first Forward pass's declarations, so
  a helper both share is declared there.
- **A mesh's `vertexColor` is the mesh's**: the particle's colour is `p.color`, as in Unreal's mesh particles.
- **Another `#type`** has no factory: `cg_material_vertex()` there fails to compile, naming itself.
