# Shaders and kernels — `.shader`, `cg_env.glsl`, engine buffers, `.compute`

Moved from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs. Loads itself, imported by the `CLAUDE.md` of the packages it describes: `api/material`, `api/shader`, `material`, `mc/shader` and `compute`. Nothing under `resources/` loads it, since all of it ships: read it before touching a `.shader`, `.compute` or `.glsl` file.

> **A shader you draw must be precached.** Name every material, keyword set, pass and kernel you add to
> `CgPipelinePrecache`, or its first draw shows late and counts `pipeline.missed`. VFX looks, kits and cues, skinned
> materials and the engine's own passes are precached for you. **[`PRECACHE.md`](PRECACHE.md)** says which is which.
> Editing a shipped shader also stales the shipped pipeline list (`PRECACHE.md` § *The shipped pipeline list*).

## Materials

A `.shader` is a material. Its first line names its domain, and it says what the surface is, not how to draw it: the
engine writes the passes, lights and fogs it, and works out what it needs.

> **Writing one? Read [`MATERIAL_FORMAT.md`](MATERIAL_FORMAT.md)**: the guide to the format, every domain, output,
> property, factory and primitive, with examples. This half of the document is the engine around a material: the
> reference files, what every program can read, the buffers the engine binds, and what it does with each output.

### The reference shaders: start here

One file a domain, shipped with the engine and built in every keyword variant by its tests, so each compiles as
written. Copy the one for what you are making:

| Domain | File | Shows |
|---|---|---|
| `surface` | [`crystalgraphics:shaders/reference/surface.shader`](../core/src/main/resources/assets/crystalgraphics/shaders/reference/surface.shader) | A world material: `Material`, a switch and a one-of in `Keywords`, `Properties`, `v2f`, a helper, `vertex()` moving the vertex, `surface()` writing a colour, a mask and a glow |
| `post` | [`crystalgraphics:shaders/reference/post.shader`](../core/src/main/resources/assets/crystalgraphics/shaders/reference/post.shader) | A post material in two steps: a half-size texture of its own, then a draw onto the scene |
| `ui` | [`crystalgraphics:shaders/reference/ui.shader`](../core/src/main/resources/assets/crystalgraphics/shaders/reference/ui.shader) | A canvas brush: the element's image, its position across the element, a per-element value |
| `global` | [`crystalgraphics:shaders/reference/global.shader`](../core/src/main/resources/assets/crystalgraphics/shaders/reference/global.shader) | A raw vertex and fragment pair: one of the renderer's own passes |

```java
CgMaterial stone = CgMaterial.load("crystalgraphics:shaders/reference/surface.shader").keyword("GLOW", true);
CgWorldRenderer.get().draw(mesh, stone).at(x, y, z).submit();
```

A kernel's reference is [`reference/kernel.compute`](../core/src/main/resources/assets/crystalgraphics/shaders/reference/kernel.compute)
(§ *Compute*).

### Where the engine's shaders live

Under `crystalgraphics:shaders/`, one folder per owner: a pass, its kernels and its includes sit together.

| Folder | What is in it | Yours to use? |
|---|---|---|
| `reference/` | One reference file a domain, and `kernel.compute` | Copy them |
| `lib/` | Include libraries: `math`, `color`, `noise`, `sdf`… (§ *GLSL Standard Library*); `lib/material/` Unreal's material expressions; `lib/vfx/` what VFX materials and factories read (`particle.glsl`, `depth.glsl`, `ribbon.glsl`…) | Include them |
| `materials/` | General-purpose materials: `emission_only`, `entity` and `entity_emissive` (a Minecraft entity and its glow layer), `sky` and `grid` (a scene's backdrop and ground) | Load them |
| `factories/` | What draws a material in the world (`VertexFactory Sprites`), and what the factories alone read: the particle records (`particle.glsl`), the bone palettes (`bones.glsl`), `draw.glsl`, `sequence.glsl`, `scatter.glsl` | Name the factories |
| `vfx/` | The VFX materials, a folder a feature (`particle/`, `trail/`, `beam/`, `air/`…), each beside the kernels its feature runs; `vfx/sim/` the particle simulation's modules, a folder a kind (`forces/`, `motion/`, `collision/`, `occlusion/`, `beam/`, `gas/`, `grid/`) and `data/` what they read | Load the materials |
| `env/` | What the engine compiles into every program: `cg_env.glsl`; `material/` what a surface and a post material take; `kernel/` a kernel's (`kernel.glsl`, `atomic.glsl`, `subgroup.glsl`, `check.glsl`); `handed/` the world's lights, sun and scene textures handed to a program that asks; the scene data any material reads, `globals.glsl` (parameter collections) and `rows.glsl` (per-draw values) | Never include |
| `world/` | The world renderer's passes; `world/lights/` lighting and shadows, `world/media/` fog volumes | The engine's |
| `post/` | The post stack's materials, its bloom and film kernels and the composite's includes | The engine's |
| `canvas/` | The canvas's materials (`CgCanvasMaterials`); `canvas/primitives/` what draws a ui material (`Primitive Quad`) and what the primitives alone read: their buffers (`quad`, `curve`, `text`, `shape`, `clip`, `palette`) and `texel`, `stroke`, `rect_blur`, `text_gamma` | Name the primitives |
| `compute/` | `compute/ops/`, the GPU ops' kernels (`CgGpuOps`), and the self test | The engine's |
| `noise/` | The baked noise volumes (`CgNoiseVolumes`) | The engine's |
| `collision/`, `loading/` | The collision debug lines; the loading screen's logo | The engine's |
| `demo/` | The demo scenes' materials | Read them |

**An engine buffer lives with its owner.** A factory's or a primitive's buffer (the particle records, the canvas's quads) sits beside it and only its file names it, in `EngineBuffers { }`; the scene data every material may read (`globals`, `rows`) is the environment's, reached by reading it (`CG_GLOBAL_*`, `PerDrawValues On`). A material names neither: only a `#domain global` pass, the renderer's own, writes `EngineBuffers`. Unreal's split: a vertex factory owns its streams, the scene's uniform buffers and parameter collections reach every material.

### The format in one screen

```glsl
#domain surface
Material { BlendMode Translucent  ShadingModel Unlit }     // every property has a default
Keywords { GLOW ("Glows")  SHAPE ("Shape") = BOX | CONE }   // #ifdef GLOW, #ifdef SHAPE_BOX
Properties {
    _Tint ("Tint", color, hdr) = (1, 1, 1, 0.5)            // hdr, range(a, b): marks for editors
    _Heat ("Heat", float, range(0, 20)) = 6
}
struct v2f { vec2 uv0; };                                    // no vertex(): uv0-3, color, normal, world, light filled
void surface(in v2f i, inout CgSurface s) {
    s.baseColor = _Tint.rgb;
    s.opacity = _Tint.a;
    s.emissive = _Tint.rgb * _Heat * i.uv0.x;                // naming emissive draws the glow
}
```

- **The outputs it names decide its passes**: `emissive` a glow, `refraction` a bend; a translucent material naming
  neither `baseColor` nor `opacity` draws no colour ([`MATERIAL_FORMAT.md`](MATERIAL_FORMAT.md) § 4.4).
- **`vertex(inout CgVertex v, out v2f o)`** moves `v.world` (camera-relative); the engine projects it. Never
  `gl_Position`. `v.normal` is world-space. `Material { VertexFactory Procedural }` draws no mesh: `vertex()` assigns
  `v.world`.
- **`VertexFactory` is what draws it**: `Mesh` (the default), `Procedural`, and the VFX engine's `Sprites`,
  `MeshParticles` (a particle renderer: `cg_Particle`, Unreal's particle parameters), `VfxMesh`, `Billboard`, `Ribbons`,
  `PathRibbons`, `Sequence` and `Tube`, `TubeClosed`, `TubeVolume` (a tube slot: `cg_Tube`, where on its path the vertex
  is, and `_FxPath` bound for you). Each is a file, `shaders/factories/<Name>.factory`; `VertexFactory mymod:Ribbons`
  reads `mymod:shaders/factories/Ribbons.factory` (§ 5.3), and `CgVertexFactory.register` adds one built in Java. A ui
  `Primitive` is `shaders/canvas/primitives/<Name>.primitive` the same way. A factory's parameters read in `surface()` reach
  the fragment field by field, only those named:

  ```glsl
  Material { BlendMode Translucent  VertexFactory Sprites }
  void surface(in v2f i, inout CgSurface s) {
      s.baseColor = cg_Particle.color.rgb;                     // passes the colour, nothing else
      s.opacity = cg_Particle.color.a * (1.0 - cg_Particle.relativeTime);
  }
  ```
- **No `#pragma`, `Pass`, `Tags` or `Queue`**: switches are `Keywords { }`, per-draw values `PerDrawValues On`, and
  `CG_GLOBAL_*` parameters or the world's lights are found by name. A file without its `#domain` line fails to load,
  saying so.
- **No parameter is special by its name**: `MaskClip` names the opacity mask's threshold, a number or a float property.
- `#domain post` writes `surface(in CgScreen i, inout CgPostSurface s)`, or one per `Step`; the engine draws the
  screen. `CgPostMaterial.of(path)` draws it at its `Material { Location ... }`.
- `#domain ui` writes `surface(in CgUiInput i, inout CgUiSurface s)` on a canvas `Primitive` (`Quad`, `Curve`, `Text`);
  clip, opacity, the antialiased edge and premultiplying are the engine's. A text style's paint is a ui material on
  `Primitive Text`.
- `#domain global` is a raw `RenderState`, `vertex(out v2f o)` and `fragment(in v2f i, out vec4 c)`: the engine's own
  passes.

### cg_env.glsl — what every program can read

`src/main/resources/assets/crystalgraphics/shaders/env/cg_env.glsl` is the foundation every program stands on: the
frame block, the per-instance object data, the instance-id bridge, the scene samplers and the convenience macros over
them. The engine includes it in every stage of every program; a material never includes it. **A renderer's own macros
are not in it**: `CG_QUAD_*` lives in `canvas/primitives/quad.glsl` and `CG_CURVE_*` in `canvas/primitives/curve.glsl`, brought only
by the primitive that binds their buffer.

#### Frame Data — `CgViewBlock` and `CgEnvironmentBlock`

Two `layout(std140)` blocks the engine binds, available in every stage: `CgViewBlock`, the pass's view, and
`CgEnvironmentBlock` (`cg_SunDirection`, `cg_FogColor`, `cg_FogParams`), the world's, which every pass of a frame
shares (`CgPassConstants`):

| GLSL name | Type | Content |
|---|---|---|
| `cg_ViewMatrix` | `mat4` | Camera view matrix |
| `cg_ProjMatrix` | `mat4` | Projection matrix |
| `cg_Time` | `vec4` | `(t/20, t, t×2, t×3)` — seconds |
| `cg_Resolution` | `vec2` | Viewport size in pixels |
| `cg_DepthParams` | `vec4` | `x` 1 when the pass's depth is reversed, the engine's convention (Unreal's: near 1, far 0), 0 in a host's standard depth (Minecraft before 26.2); `y` 1, clip depth 0..1 on every device; `z` 1 when it draws into the linear HDR scene (`CG_LINEAR_SCENE`), `w` the scene's glow gain (`CG_SCENE_GLOW`). Read through `cg_LinearEyeDepth`, or `cg_EngineDepth` for a raw depth against a matrix of the engine's handed in as a property; not directly |
| `cg_WorldOrigin` | `vec4` | Where world space's origin is in absolute coordinates: the camera, in a camera-relative world pass. Read through `CG_ABSOLUTE_WORLD_POS(p)` |
| `cg_SunDirection` | `vec4` | `xyz` the direction toward the sun (the moon while it is down), `w` daylight 0..1. Read through `CG_SUN_DIRECTION`, `CG_DAYLIGHT` |
| `cg_FogColor` | `vec4` | The world's fog colour, `a` 1 when there is fog |
| `cg_FogParams` | `vec4` | `x` fog start, `y` fog end, in blocks from the eye. Read through `cg_FogAmount`. `z` the sun's direct share of a full day's sky light (`crystalgraphics.world.sunShare`, 0.6), read through `CG_SUN_SHARE`. `w` 1 where entities take the Nether's two lights (`CG_ENTITY_LIGHT1`) |
| `cg_PrevViewProj` | `mat4` | This pass's camera-relative points to last frame's clip space, for a temporal effect. Read through `cg_PreviousClip(p)` |
| `cg_FrameParams` | `vec4` | `x` the host frame's index (0 to 65535), `y` 1 while last frame was one of this view, `w` 1 when the target is the host's own (`CG_ENCODED_TARGET`). Read through `CG_FRAME_INDEX`, `CG_HISTORY_VALID` |

**Scene samplers**, bound by the engine for every draw; never declare or bind them yourself, nor bind a property to
their units:

| GLSL name | Type | Unit | Content |
|---|---|---|---|
| `cg_DepthBuffer` | `sampler2D` | `CgBindingModel.SCENE_DEPTH` | The target's depth as it stands at the draw: a copy the frame graph takes before the first reader and again after a draw that wrote depth (`render/graph/CLAUDE.md` § *Reading the target*). An opaque material sees the host's world and the opaque draws sorted before it, a transparent one every opaque draw. **Raw values are the host's convention**, reversed-Z on 26.2, so compare eye distances: `CG_SCENE_EYE_DEPTH(uv)` against `cg_LinearEyeDepth(gl_FragCoord.z)`. Both stages |
| `cg_SceneColor` | `sampler2D` | `CgBindingModel.SCENE_COLOR` | The target's colour as it stands at the draw, copied the same way: everything sorted before the reader, transparent draws included, but other readers in a row with it, which share its copy. Read through `CG_SCENE_COLOR(uv)`; a material reading it sets `SceneColorMargin` |
| `cg_Lightmap` | `sampler2D` | `CgBindingModel.LIGHTMAP` | The host's lightmap in a world pass, block light along u and sky light along v; 1x1 white in any other pass. Read through `CG_LIGHTMAP(light)` |
| `cg_Media` | `sampler3D` | `CgBindingModel.MEDIA` | The smoke and gas in front, a froxel volume over the screen (`CgWorldMedia`): rgb the light the world's lights scattered toward the eye, alpha what gets through; 1x1x1 white where nothing is in the air or the pass is not a world pass. Read through `cg_MediaTransmit(uv, eyeDepth)` and `cg_MediaLight(uv, eyeDepth)` |
| `cg_LocalLights` | `sampler3D` | `CgBindingModel.LOCAL_LIGHTS` | The world's lights on transparent surfaces, the translucency lighting volume (`CgWorldLights`, `docs/LIGHTS.md` §8) in a transparent world pass; 1x1x1 black in any other. Read through `cg_LocalLightAt(position)`. The lowest unit the engine reserves: properties stay below it |

Convenience macros over the frame block:

| Macro | Expands to | Use |
|---|---|---|
| `CG_TIME` | `cg_Time.y` | Raw seconds: the one you want almost always |
| `CG_TIME_VEC4` | `cg_Time` | Full 4-component time vector |
| `CG_RESOLUTION` | `cg_Resolution` | Viewport dimensions in pixels |
| `CG_SCENE_EYE_DEPTH(uv)` | `cg_LinearEyeDepth(texture(cg_DepthBuffer, uv).r)` | Scene distance from the camera at `uv`, in eye units, under any depth convention |
| `CG_SCENE_COLOR(uv)` | `texture(cg_SceneColor, uv)` | The scene's colour at `uv` (`gl_FragCoord.xy / CG_RESOLUTION` for the pixel behind) |
| `CG_MATRIX_MVP` | `cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD` | Standard MVP transform, for a `global` program |

#### Lighting and fog — on by default

A surface material is lit by the host's lightmap and fogged as Minecraft's own blocks are, with no code of its own:
after `surface()`, the engine lights `s.baseColor` and fogs the result.

```glsl
Material { BlendMode Additive  ShadingModel Unlit }      // an emissive effect: fogged, never darkened
Material { ShadingModel Unlit  Fog Off }                 // a sky, or a haze that bends what is already fogged
```

| Name | Is |
|---|---|
| `s.light` | the `vec2(block, sky)` light it is lit by: the draw's (`CG_OBJECT_LIGHT`) unless `surface()` or its factory sets it (a particle's own) |
| `CG_OBJECT_LIGHT` | the draw's light, in the normal matrix's unused column: `CgWorldRenderer`'s `.light`, else the world's at its position |
| `CG_LIGHTMAP(light)` | the lightmap's colour for a `vec2(block, sky)` |
| `cg_Lit(color)`, `cg_Fog(color)` | fragment only: what the engine applies, for an `Unlit` material or a `global` program wanting them on part of its colour. `cg_Lit` is the lightmap times `1 +` the world's lights at the fragment (`cg_LocalLights`, transparent passes) |
| `cg_LocalLightAt(p)`, `cg_FragmentPosition()` | the world's lights at camera-relative `p`, non-directional; this fragment's camera-relative position |
| `cg_FogAmount(distance)`, `CG_FOG_AMOUNT(worldPos)` | 0 to 1, vanilla's smoothstep from `cg_FogParams` |

- **Colour is authored in sRGB**, as Minecraft's is (`ColorSpace sRGB`, the default). Where a pass draws into the HDR
  scene (`CG_LINEAR_SCENE`), the engine decodes the colour after light and fog: a premultiplied one unpremultiplied,
  and a value above 1 keeps its excess unless the material glows, when it is held at white and its glow carries the
  light past it. A blend over what is behind also has its alpha remapped (`1 - decode(1 - a)`), so smoke hides as much
  as it did in the encoded picture. The glow is linear always. `Material { ColorSpace }` says what else a colour is:

  | `ColorSpace` | The colour is | Into the HDR scene | Onto the host's own target | Into a texture of ours |
  |---|---|---|---|---|
  | `sRGB` (surface's default) | authored as Minecraft's are | decoded | as written | as written |
  | `Linear` (post's default) | linear light, as Unreal's material output is: a port | as written | encoded (`CG_ENCODED_TARGET`) | as written |
  | `Target` (post and ui) | already in its target's encoding: a picture-space effect | as written | as written | as written |

- **A value tuned per path** is `CG_HDR(off, on)`: `on` under the scene, `off` without it, picked at runtime in one
  program. The decode shrinks a dim colour (0.3 becomes about 0.07), so faint light that adds reads dimmer under the
  scene: `s.baseColor = colour * light * CG_HDR(1.6, 3.0);`. The VFX shaders carry their HDR value alone: the scene is
  their default path.
- A material setting `s.light` reads `CG_LIGHTMAP` with it too: a smoke billow mixes its fire glow out of the
  lightmap's reach.

#### Lit by its normal: `TranslucencyLighting Surface`

A translucent material lit by the direction of the world's lights at its normal, not the same from every side: Unreal's
Surface translucency lighting (`TLM_Surface`). It writes `s.normal`; the engine lights the colour by it, then adds
`s.emissive` unlit, before fog and the blend.

```glsl
Material { BlendMode Translucent  TranslucencyLighting Surface }
void surface(in v2f i, inout CgSurface s) {
    s.normal = normalize(i.tangentToWorld * normalXy(texture(_Normal, i.uv0).xy));
    s.baseColor = baseColour;
    s.opacity = opacity;
}
```

- **Translucent blend modes only**: an opaque material is lit by the world's lights already. Without `s.normal` it
  fails to load, naming it.
- The light is Unreal's `GetVolumeLightingDirectional`: the translucency volume's light (`cg_LocalLightAt`) plus its
  direction (`cg_LocalLightDirectional`) at the normal, times the lightmap as `cg_Lit`. A firing drawing one fills the
  volume's direction too, at twice the volume's cost (`LIGHTS.md` §8).
- `CgMaterial.surfaceLit()` says whether a material is one.

#### What a program carrying its own lighting reads

A ported program that keeps its game's lighting formulas (an `Unlit` material, or a `global` program) reads the lights
the engine hands each draw, in world passes, in both stages unless said:

| Name | Is |
|---|---|
| `CG_DIRECTIONAL_LIGHT_DIRECTION`, `CG_DIRECTIONAL_LIGHT_COLOR` | the directional light, toward it (`CG_SUN_DIRECTION`: the sun, or the moon while the sun is down); its colour, the sun's part of the lightmap at the draw's light |
| `CG_SKY_LIGHT_COLOR` | the sky light's colour: the ambient, the lightmap at the draw's light less the directional's |
| `CG_ENTITY_LIGHT0`, `CG_ENTITY_LIGHT1` | Minecraft's two fixed lights on entities, in world axes, normalised; the second from below where the host says the dimension lights entities as the Nether does (`CgHostEnvironment.netherLighting()`). Vanilla's shading is `min(1, (max(0, n·L0) + max(0, n·L1)) * 0.6 + 0.4)` |
| `CG_DRAW_POINT_LIGHT_POSITION(i)`, `_COLOR(i)`, `_PARAMS(i)` | the four point lights nearest the draw's origin, `i` 0 to 3, nearest first, camera-relative; `COLOR` is linear times intensity, and `PARAMS` is (1, inner radius, radius, falloff: 0 linear, 1 inverse square); `PARAMS.x` 0 where there are fewer |
| `int cg_NearestLights(vec3 p, out int idx[4])` | the same at any camera-relative `p`, as indices for `cg_LightPosition(i)`, `cg_LightColor(i)`, `cg_LightInnerRadius(i)`, `cg_LightRadius(i)` and `cg_LightFalloff(i)` (0 inverse square, else Unreal's exponent); answers how many, the rest of `idx` -1 |
| `CG_DIRECTIONAL_SHADOW(p)`, `_COLOR`, `_WRAP` | fragment only: the casting directional's visibility at `p`, 1 lit; the colour a shadow tints toward; a wrap of N.L |

- The directional and the sky split the lightmap at the draw's light (`CG_OBJECT_LIGHT`) as the world's lights split
  it for the sun's shadow: the sun takes `CG_SUN_SHARE` of what sky light adds by day (`CG_DAYLIGHT`), and the sky
  keeps the rest, block light included. By night the directional is black.
- **The point lights are the world's light grid** (`env/handed/lights.glsl`): a program naming `cg_NearestLights` or
  `CG_DRAW_POINT_LIGHT_*`, itself or in a file it includes, is handed the grid's three buffers after its own
  `Buffers { }` (leaving it one) and the grid's placement as `_cg_Light*` properties. A transparent firing reads this
  frame's grid, an opaque one the last frame's: the grid is binned in the transparent firing. A particle's origin is
  its system's.
- **The shadow is the sun's cascaded shadow map of our casters** (`env/handed/sun.glsl`, `LIGHTS.md` §7): a program
  naming `CG_DIRECTIONAL_SHADOW` is handed the map as `_cg_SunMap` and its cascades as `_cg_Sun*` properties, read
  with the sample pass's PCF, the last cascade fading to lit. Only a transparent firing has the map: in an opaque one
  it reads 1, since the deferred pass shadows our opaque pixels already. Minecraft's sun lights nothing past the
  terminator, so `_WRAP` is 0.

#### Per-Instance Object Data

Every draw uses instancing. Per-object data lives in an engine-managed storage block,
`layout(std430) readonly buffer CgObjectDataBuffer { CgObjectData cg_Objects[]; }`. The buffer-texture path below
GL 4.3 is unhooked and documented for reconnecting in [`TBO_PATH.md`](TBO_PATH.md). The struct:

```glsl
struct CgObjectData {
    mat4 modelMatrix;
    mat4 normalMatrix;
    vec4 custom0;
    vec4 custom1;
    vec4 custom2;
    vec4 custom3;
};
```

| Macro | Expands to | Notes |
|---|---|---|
| `CG_OBJECT_TO_WORLD` | `CG_OBJECT_DATA.modelMatrix` | Model → world transform |
| `CG_NORMAL_MATRIX` | `mat3(CG_OBJECT_DATA.normalMatrix)` | Upper-left 3×3, for transforming normals |
| `CG_OBJECT_CUSTOM0`–`CG_OBJECT_CUSTOM3` | `CG_OBJECT_DATA.custom0` … `.custom3` | Per-instance `vec4` slots: a world draw's `custom(slot, …)` |
| `CG_INSTANCE_ID` | `CG_DRAW_INSTANCE + gl_BaseInstance` (vertex) / `cg_InstanceId` (fragment) | Instance index, bridged as a flat varying so the fragment has it. With `CG_SHARED_RECORD` (bit 30) set, the rest of it names the one record every instance reads: an indirect `INSTANCES` draw's |
| `CG_DRAW_INSTANCE` | `gl_InstanceID` (vertex only) | The instance's index in its own draw: in an indirect `INSTANCES` draw, the element it draws |
| `CG_VERTEX_ID` | `gl_VertexID - gl_BaseVertex` (vertex only) | The vertex's index in its own mesh, wherever the mesh sits in the buffer it is drawn from |
| `CG_VERTEX_CORNER` | `vec2` from `CG_VERTEX_ID` (vertex only) | The corner of a `CgMesh.quads(n)` vertex: (0,0), (1,0), (1,1), (0,1) around each quad |

Every draw carries its bases in the call (GL 4.6's draw parameters, `GL_ARB_shader_draw_parameters` below), so a run
the executor joins into one multi-draw (`render/graph/CLAUDE.md` § *Multi-draw*) runs the program a draw alone runs.

#### Vertex attributes

What a factory's `fill()` and a `global` program's `vertex()` read; a surface material reads them through `CgVertex`
(`v.position`, `v.normal`, `v.color`, `v.uv0` …). Vertex stage only, placed by the vertex format:

| Name | Type |
|---|---|
| `cg_Position` | `vec3` |
| `cg_Normal` | `vec3` |
| `cg_Color` | `vec4` |
| `cg_TexCoord0` … `cg_TexCoord3` | `vec2` |

Unreal's local vertex factory's slots, in its order. A mesh written with no colour reads white, and a UV set it was
not given repeats the set before: Unreal's null colour stream and its repeated UV channel.

**On a skinned mesh** every material on the `Mesh` factory draws its `CG_SKINNED` variant, the skinned vertex factory:
the engine declares `cg_BoneIndices`, `cg_BoneWeights` and the draw's palette (`factories/bones.glsl`), and
`cg_Position` and `cg_Normal` read as the vertex skinned by its bones, so a material needs no code for it. The mesh
picks the variant (`CgPipelineForm.SKINNED`), in every pass; a material on a factory reading no mesh fails to compile
on one.

#### A material's values

A material's non-sampler properties are one std140 block, `CgMaterialBlock`, read under their own names. A material
drawn by object records keeps its materials' blocks side by side in its **material arena** (`CgMaterialArena`, Bevy's
material slab): one storage buffer of slots, each draw's instance id naming its material's slot, so draws of two
instances that differ only in their values join in one multi-draw. The values are globals, loaded from the draw's slot
first thing:

```glsl
s.baseColor = texture(_MainTex, i.uv0).rgb * _Color.rgb;   // the same either way
```

- A property name is a global: a local of the same name hides it.
- Textures are still bound per material: instances with different textures do not join.

#### Stage defines — `CG_VERTEX_STAGE` / `CG_FRAGMENT_STAGE` / `CG_COMPUTE_STAGE`

One `.shader` becomes **two** GLSL programs, and every `#` line at file scope, `#include` included, reaches **both**.
There is no stage filtering, by design: an author writes one file, not two. A kernel is a third stage, and includes
`cg_env.glsl` and its engine buffers' env files like any material.

> **A lib included at file scope is compiled into the vertex stage too, and an env file into every stage. If any of it
> is fragment-only, it must guard itself.**

The compiler emits exactly one of these into each generated source, **before** the includes, so a lib can see it:

| Define | Present in |
|---|---|
| `CG_VERTEX_STAGE 1` | generated vertex source only |
| `CG_FRAGMENT_STAGE 1` | generated fragment source only |
| `CG_COMPUTE_STAGE 1` | a kernel's source |

```glsl
#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
float sdf_coverage(float dist) { return 1.0 - smoothstep(-fwidth(dist), fwidth(dist), dist); }
#endif
```

**Name the stages that cannot have it, never `#ifdef CG_FRAGMENT_STAGE`**: a file read with no stage define at all
would lose the function. A kernel drops every function it does not call, so a lib's guard only matters to one that
calls it; an env file's guard is what lets a kernel use its buffer.

> NVIDIA accepts derivative builtins in a vertex shader and AMD correctly refuses: `sdf.glsl`'s unguarded `fwidth`
> once kept the UI gallery from launching on AMD alone.

Fragment-only, never legal in a vertex shader or a kernel: `fwidth`/`dFdx`/`dFdy` and their `Fine`/`Coarse` variants,
`discard`, `gl_FragCoord`, `gl_FrontFacing`, `gl_PointCoord`, `gl_FragDepth`, `interpolateAt*`,
`gl_SampleID`/`gl_SamplePosition`/`gl_SampleMask`.

- **`ShippedShaderStagePurityTest`** (in both CrystalGraphics and CrystalGUI) compiles every shipped `.shader` and
  asserts no such name is *reachable* in the generated vertex source; **`ShippedKernelStagePurityTest`** does the same
  for every shipped kernel. Both resolve the stage conditionals through `CgShaderStages`. GL-free.
- **`--mode=shader-compile-audit`** puts the real driver over every shipped shader, kernel and keyword variant,
  collecting failures instead of stopping at the first. Run it on any GPU that disagrees.

#### Loops — every count has a ceiling

> **A loop runs a number of times a constant bounds: a literal, a constant or macro, or `min(data, CONSTANT)`.**

A loop counted by data (a light list, an entity count, a property) runs as often as that data says, on every pixel. A
light-grid cell of thousands of lights walked at each of 16,000 pixels ran the GPU past Windows' two-second timeout,
and the driver reset, blanking every monitor. A cap turns that into a cost.

```glsl
for (int k = 0; k < min(_Entities, CG_ENTITY_BOXES_MOST); k++) { ... }   // the CPU's cap, mirrored and named
```

- **A light list** takes `lights.glsl`'s candidates: at most `CG_LIGHT_CANDIDATES` a walk, evenly strided from a
  per-pixel offset (`CG_LIGHT_OFFSET`), a sum over them scaled by `CG_LIGHT_STRIDE`: ReSTIR's uniform candidates, so a
  crowded cell costs 64 lights and stays unbiased.

  ```glsl
  int count = span.y - span.x, walked = min(count, CG_LIGHT_CANDIDATES);
  float offset = CG_LIGHT_OFFSET(px, CG_FRAME_INDEX);
  for (int c = 0; c < walked; c++) sum += lit(INDICES(from + span.x + CG_LIGHT_CANDIDATE(c, count, offset)));
  sum *= CG_LIGHT_STRIDE(count);
  ```

- **A count the CPU caps** is capped again where it is read, the constant named after its Java owner
  (`CgWorldEntityBoxes.MAX`, `CgWorldLights.MOST_CASTERS`). An authored count (octaves, a blur radius) gets a cap past
  which more is invisible.
- **A loop bounded some other way** (a binary search, a work group's stride over a buffer) says why on its line:
  `while (lo < hi) {   // LOOP-BOUND: a binary search`.

**`ShippedShaderLoopBoundsTest`** reads every shipped `.shader`, `.glsl` and `.compute` and fails on a loop with neither
a visible constant bound nor a `LOOP-BOUND` note.

---

### Keywords: a program per combination

`Keywords { }` ([`MATERIAL_FORMAT.md`](MATERIAL_FORMAT.md) § 4.6) declares compile-time switches. Turning one on
defines it in both stages. **Every combination turned on is its own program**, compiled once and cached: no runtime
branch.

| Rule | Detail |
|---|---|
| At most 31 a material | More fails to load; past 8 a material's variants sit in a sparse table |
| Upper case | Names match `[A-Z][A-Z0-9_]*`; a one-of's values are `NAME_VALUE` |
| Declared names only | `keyword(name, true)` throws for a name its material does not declare |
| Compiled lazily | At its first draw (or a precache), then cached under its `ProgramKey` |
| Off by default | A material turns on only what it asks for |

```java
CgMaterial mapped = material.keyword("NORMAL_MAP", true);   // another material, made once; material is unchanged
boolean on = mapped.isKeywordEnabled("NORMAL_MAP");         // true; material's stays false
```

**A keyword the renderer switches at each draw** (a tier's cheap filter, the HDR scene, a fade) is marked `switched`,
and a one-of always is: together they are the material's **permutation domain**, Unreal's `FShaderPermutationDomain`,
and a precache builds every combination, so nothing lists variants by hand.

```glsl
Keywords { FILTER ("Filter") = KARIS | CHEAP   FADE ("Fades", switched) }
```

```java
pipelines.addPass("mymod:shaders/post/blur.shader");             // every combination
pipelines.add("mymod:shaders/rune.shader", "GLOW");              // each combination with the material's own GLOW
```

- **Only what a renderer switches.** A keyword set once on a material (a look's feature, a VFX slot kind) is the
  material's: precaching the material covers it.
- A domain spans at most 256 combinations (`CgShaderPermutations.MAX_VARIANTS`); more fails to load.
- A keyword passed to `add` or `addPass` pins its dimension: `addPass(shader, "FILTER_CHEAP")` builds only the
  combinations with it.
- A draw of a material with a domain that still misses its pipeline is logged once whatever
  `crystalgraphics.pipeline.logMisses` says: it was never added, or the draw binds two values of one one-of.

---

### Engine buffers

`CgViewBlock`, `CgEnvironmentBlock` and `CgObjectDataBuffer` are declared in every program. A buffer only some programs
need is bound by what draws them: a factory or primitive names it in `EngineBuffers { }` (`MATERIAL_FORMAT.md` § 5.3,
§ 8.3), and every material on it reads its macros. A surface or ui material names none of these itself.

| Token | Provides | Bound by |
|---|---|---|
| `quad` | `QUAD_DATA(n)` + `CG_QUAD_WORLD_POS` / `CG_QUAD_UV` / `CG_QUAD_COLOR` / `CG_QUAD_NORMAL` / `CG_QUAD_ATLAS_LAYER` / `CG_QUAD_CUSTOM0`-`CG_QUAD_CUSTOM1` (two free vec4s an element, written with `Quad.custom0`/`custom1`: where a per-element value belongs rather than in a property that breaks the batch), and for screen-space materials the edge and texel antialiasing below | The `Quad` and `Text` primitives: `CgQuadBatch`, UI quads, text glyphs |
| `curve` | `CURVE_DATA(n)` + `CG_CURVE_WORLD_POS` / `CG_CURVE_P0`–`P2` / `CG_CURVE_COLOR0`–`1` / `CG_CURVE_WIDTHS` / `CG_CURVE_FEATHER` / `CG_CURVE_FLAGS` | The `Curve` primitive: `CgCurveBatch`, Bézier strokes, graph wires |
| `palette` | `PALETTE_DATA(n)` + `cg_spatial_point`/`_vector`/`_covector`/`_scale`, `cg_effect_opacity`, and fragment-only `cg_spatial_from_fragment`: the raster pass's property trees (`CgPalette`), each spatial node's affine into the target, each effect node's opacity | `quad`, `curve` and `clip` bring it; `CG_QUAD_*` and `CG_CURVE_*` positions are already mapped through it. The primitive multiplies by the item's opacity |
| `clip` | `CLIP_DATA(n)` + `CG_CLIP_QUAD_COVERAGE` / `CG_CLIP_CURVE_COVERAGE`: the coverage of the `CgClipTable` entry the element names (`Quad.clip`, `Curve.clip`, `CgTextDraw.clip`), 1 for entry 0. Fragment stage only | Every ui primitive, so every ui material is clipped |
| `rows` | Every numeric property read per draw: the material's value, or the draw's row in `CgPropertyRows` where its binder says so ([below](#per-draw-values-perdrawvalues-on)) | `Material { PerDrawValues On }` |
| `particle` | `PARTICLE_DATA(n)` + `CG_PARTICLE_POSITION` / `_SIZE` / `_VELOCITY` / `_PROGRESS` / `_SEED` / `_SPIN` / `_HEAT` / `_OPACITY` / `_LIGHT` / `_ID` / `_FLOOR` / `_COLOR` / `_SCALE` / `_AGE`: a GPU pool's drawn parts (`CgParticleBuffer`), written after each step at each particle's record index; a draw reads its own range of Range's order. It brings `rows` | The particle factories (`Sprites`, `MeshParticles`), which hand it to a material as `cg_Particle` |
| `globals` | `CG_GLOBAL_<namespace>_<name>` for every parameter of every `CgParameterCollection`: Unreal's material parameter collections ([below](#global-parameters-cg_global_)) | Any program naming one |

> **A screen-space quad material antialiases its own edges, without MSAA.** `canvas/primitives/quad.glsl` provides
> `CG_QUAD_EDGE_PARAM` (the vertex's parameter, grown by half a pixel when the element is rotated or sheared in device
> space), `CG_QUAD_EDGE_WORLD_POS(param)`, `CG_QUAD_EDGE_UV(param)` (clamped, so the pad never samples past the rect)
> and `CG_QUAD_EDGE_COVERAGE(param)`: the exact area a straight edge leaves of the pixel. Axis-aligned elements are left
> alone: the rasteriser snaps those, and two abutting quads softened on a fractional boundary would seam. The Quad
> primitive applies it; a 3D quad must not (half a pixel means nothing under a perspective projection).
> `cg_texel_aa_sample` (`canvas/primitives/texel.glsl`) is the pixel-art filter: nearest everywhere, one screen pixel of blend at a
> texel boundary.

> **A record names a spatial and an effect node** (`Quad.node`, `Curve.node`, `CgTextDraw.node`), recorded in a
> `CgRecording`'s `spatial()` and `effects()` trees; node 0 is the target's own space, at opacity 1. The executor writes
> each raster pass's palette from the recorded values and a `CgPropertyValues`, so a compositor moves or fades what was
> recorded by writing values and executing the built frame again.

> **A rounded clip is an element's field, not a render target.** `CgClipTable.add` records a rounded box in its own
> space, the inverse of the pose that put it in the bound target, and the entry it sits inside; it answers an index.
> An element stamped with it multiplies its output by the coverage of every entry up the chain (`MAX_DEPTH`, 4), so any
> rotation or skew clips exactly. Changing the clip never flushes. `addPixelRect` is the square case at whole pixels.

**`curve` is read in both stages**: a stroke is an analytic distance evaluated per pixel, so the fragment re-reads
`CURVE_DATA(CG_INSTANCE_ID)` for its control points.

A buffer's GLSL is declared **while the program is parsed, before anything can compile**, so its names exist whatever
compiles it first. A token may carry an env file of macros written against it (`shaders/<owner's folder>/<token>.glsl`),
included right after the declaration. Register a mod's own with `CgEngineBufferRegistry.register(...)`: providers hold
a `Supplier`, so registering never makes the buffer before a context exists. Never attach a buffer from Java: bound
from the program's declaration, a compile before the first draw already has it.

| Mistake | Result |
|---|---|
| Reads `CG_QUAD_*`/`QUAD_DATA` that nothing it is drawn by binds | Fails to load, naming the buffer and `EngineBuffers` |
| An `EngineBuffers` block naming an unregistered token | Fails to load, listing the registered tokens |

#### Per-draw values: `PerDrawValues On`

A material's properties are one block for every draw of it. Under `PerDrawValues On` each numeric property may come from
the draw instead: a row of vec4s in `CgPropertyRows` that whoever draws writes once a frame, so draws with different
values still batch and instance. Godot's `instance uniform` is the same idea; here a row holds only the properties
bound per draw, and every other one stays the material's.

```glsl
Material { BlendMode Additive  PerDrawValues On }
Properties {
    _Color ("Colour", color) = (1, 1, 1, 1)
    _Bands ("Bands", color[3]) = (1, 1, 1, 1), (0, 1, 1, 1), (0, 0.5, 0.9, 1)
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = (_Color * _Bands(1)).rgb; }   // the row's where bound
```

- **Java sets a material's value by its declared name**, as for any property. Who binds a property per draw sets its
  place in the row, `CgMaterialProperty.rowSlotName(name)`; -1 is unbound.
- **An array** is `color[n]` or `vec4[n]`, n at most 256, read as `_Name(i)` with per-draw values or without.
- The VFX engine is the binder: a renderer's `bind` ([`vfx/effects.md`](vfx/effects.md) § *Constants, binds and keywords*).
- **`_cg_Exposure`** is every such material's, 1 by default: the engine multiplies a `ColorSpace Linear` colour and its
  glow by it, never a modulate blend's. The VFX engine writes its effect's `exposure(...)` there for a material that
  reads it (`CgMaterial.readsExposure()`): a game's effect authored under that game's camera exposure.

#### Global parameters: `CG_GLOBAL_*`

Values set once and read by every material naming them: Unreal's material parameter collections. A collection is
declared in Java, its parameters typed handles; a material reads each by namespace and name, which binds the buffer.

```java
public static final CgParameterCollection SKILL = CgParameterCollection.of("skill");
public static final CgParameterCollection.Scalar DARKEN = SKILL.scalar("darken", 0f);
public static final CgParameterCollection.Vector TINT = SKILL.vector("tint", 0xFFFFFFFF);
DARKEN.set(0.6f);   // every material reading it, from this frame's world draws
```

```glsl
s.baseColor *= (1.0 - CG_GLOBAL_skill_darken) * CG_GLOBAL_skill_tint.rgb;   // a float, a vec4; either stage
```

- **Declare a collection's parameters before its first use** (a set, a read, the first frame, a compile against it):
  it is laid out then, scalars packed four to a vec4 and vectors after, as Unreal lays a collection out, and a
  declaration after throws. A collection defined after a material compiled recompiles that material next frame.
- **Uploaded once a frame by the world renderer**: a pass outside the world reads the values of the last frame the
  world drew.
- At most `CgParameterCollection.MAX_VEC4S` (1024) vec4s for every collection together.

### Reading a kernel's buffers

A material reads buffers a kernel wrote by declaring them in `Buffers { }` as a kernel does, every one `readonly`, and
reads element `i` as `NAME(i)` in either stage:

```glsl
#domain surface
Material { BlendMode Additive  ShadingModel Unlit  VertexFactory Procedural }
struct Spark { vec4 positionLife; vec4 velocitySeed; };    // declared before Buffers { }
Buffers {
    SPARKS ("Sparks", Spark, readonly)
    LIVE   ("Live",   uint,  readonly)                     // the live sparks' indices, compacted
}
struct v2f { float life; };
void vertex(inout CgVertex v, out v2f o) {
    Spark p = SPARKS(LIVE(CG_VERTEX_ID >> 2));              // quad n draws the nth live spark
    vec3 centre = (CG_OBJECT_TO_WORLD * vec4(p.positionLife.xyz, 1.0)).xyz;
    v.world = centre + vec3(CG_VERTEX_CORNER - 0.5, 0.0) * 0.1;
    o.life = p.positionLife.w;
}
void surface(in v2f i, inout CgSurface s) { s.baseColor = vec3(1.0, 0.6, 0.2) * clamp(i.life, 0.0, 1.0); }
```

```java
sparkMaterial.buffer("SPARKS", sparks).buffer("LIVE", live);   // graph buffers; any thread
world.draw(CgMesh.quads(CAPACITY), sparkMaterial).indirect(count, 0, CgIndirect.INDICES, 6).at(x, y, z).submit();
```

- **The kernel's element rule**: a `float`, `int` or `uint`, their 2- and 4-vectors, or a struct of `vec4`, `ivec4`
  and `uvec4` only, since every context must read it. Upper-case names not starting `CG_`; at most 4.
- **Bound as a texture is**: `material.buffer(name, buffer)` holds for the draws captured after it, and the graph orders
  each draw after the pass writing the buffer. A history buffer reads its newest version.
- **Recorded draws only**: a material's `bindings()` (the world renderer, `pass.draw`, a chunk) binds them; `bind()`
  binds none. Every declared buffer is bound before the first capture, which throws naming one that is not.
- A `vertex()` reading a buffer runs in the depth pass too.

---

### What the engine does with each output

`surface()`'s outputs decide which passes a material draws in (`MATERIAL_FORMAT.md` § 4.4). What each of those passes
does:

#### The glow: `s.emissive`

The light a material gives off. `CgWorldRenderer` adds it into the HDR scene, where bloom takes what passes white
(`docs/ENGINE_API.md` § *CgWorldRenderer*): Unity's, Godot's and Unreal's emission, a material output added to an HDR
scene colour.

- **Linear light, unlit**, fogged additively. Past 1 it blooms: bloom takes the scene's light past `CgBloom.threshold`
  (1), and the composite rolls what passes white toward white, keeping its hue.
- **Times `CG_SCENE_GLOW`** (`CgWorldRenderer.sceneEmission`, 0.5: a stop down, since a strength set for a blurred
  bloom floods a sharp core).
- **Folded into the colour's draw where it can**: a translucent material's glow costs no draw of its own when it blends
  as its colour does (`CgShaderSpec.emissionMerge`, `CgPipeline.sceneFold`). Otherwise it draws apart, a
  transparent draw's right after its colour and an opaque one's before the transparent pass, so what is in front covers
  it.
- **Hidden by the scene, not by a depth test**: the glow's target has no depth, so a fragment further than the scene's
  depth at its pixel is discarded, with slack for the depth buffer's precision (`CG_EMISSIVE_DEPTH_SLACK`).
- **A glow that draws nothing in the scene** writes only `emissive`.
- `-Dcrystalgraphics.post.debug=level<N>` draws one stage of bloom over the frame (`docs/DEBUG_FLAGS.md`).

#### The bend: `s.refraction`

Where each pixel behind the material takes its colour from: an offset in screen UV, `s.refractionSplit` a chromatic
split, `s.refractionDepth` the eye depth the haze starts at. `CgWorldRenderer` adds every visible draw's bend into one
RGBA16F target after the transparent pass, then applies it to the scene once (Unreal's distortion pass, HDRP's
distortion vectors). A heat haze or a shockwave writes a bend instead of sampling the scene itself.

```glsl
s.refraction = haze(i.uv0) * 0.02;                // 0.02 is 2% of the screen
s.refractionSplit = 0.3;                          // red bends 1.3x as far, blue 0.7x
s.refractionDepth = FX_EYE_DEPTH(ray, enter);     // a haze traced in a volume starts where its ray enters
```

- **Bends add**: overlapping hazes sum rather than each bending the last, and nothing seams where they meet. Fade one
  out by scaling its bend, never by opacity.
- **Splits add too**, held to 0.3; scale a fading haze's split with its bend.
- **A pure haze is one draw**: writing only `refraction`, it draws the distortion pass alone.
- **A haze bends what sorts before it.** A material that must not be bent by the hazes sorted before it
  (`TranslucencyPass AfterDistortion`, `.afterDistortion()` for one draw) gets an apply of those hazes placed just
  before it, cut to their rect; nearer hazes still bend it. Up to four such applies overlap on screen.
- **Hidden by the scene, not by a depth test**, as the glow is.
- **The apply** mirrors UVs at the screen's borders and spreads red and blue by the split. A bend whose farthest tap
  would read anything nearer than the haze is shortened to stop at that edge: the foreground is never pulled in. The
  eye depth a haze gives decides this, so give the depth where it starts, not its far wall's.
- **The glow bends too**: the emission bloom blurs is bent by the same offsets first. Any post effect reading a side
  input of the scene does the same with `post.distorted(texture)`.
- `-Dcrystalgraphics.post.debug=distortion` shows the target; `--mode=distortion` is the gate.

#### Depth from a translucent material: `ClipDepth`, `DepthPrepass`

A translucent material writes no depth, so whatever blends over it later, its own back faces included, shows through.
Two settings make it write depth where `s.opacityMask` passes `MaskClip`, Godot's Alpha Scissor and Depth Pre-Pass:

```glsl
// ClipDepth: one draw. Below the threshold the pixel is cut; above it, drawn and its depth written. Ink, debris.
Material { BlendMode AlphaComposite  ClipDepth On }
void surface(in v2f i, inout CgSurface s) {
    float cover = 1.0 - smoothstep(1.0 - 2.0 * aa, 1.0, across);
    s.opacityMask = cover;
    s.baseColor = colour;
    s.opacity = cover;
}

// DepthPrepass: soft throughout, opaque in its middle. The depth of where it is solid first, then the blend over it,
// so only its nearest solid surface is shaded and what is behind it is hidden there. Smoke.
Material { BlendMode Translucent  DepthPrepass On  MaskClip 0.9 }
```

- **ClipDepth** cuts below the threshold and writes depth where it draws.
- **DepthPrepass** draws a depth pass of every such draw before the transparent pass, cutting below the threshold, then
  the blend with depth write off. Compute only what decides `opacityMask` before writing it: in the depth pass the
  material stops there. Every vertex stage is `invariant gl_Position`, so the two passes agree on depth exactly.
- **A ClipDepth material draws first in its group** (`CgSortKey`'s solid bit), before the rest of its effect whatever
  its `order`: what blends over it then sees its depth. A DepthPrepass material keeps its order.
- **A DepthPrepass material should not fade against the scene's depth** (soft particles): from the transparent pass on,
  that holds its own prepass, and it would fade into itself. Fade by something else, as `billow.shader` fades by its
  height over the surface it rests on.
- **Cut before a fade into an opaque surface.** Where it thins only to meet the ground, take `opacityMask` from the
  density before that fade: the ground shows through, and what draws later behind it stays hidden.

#### Dimmed by the smoke in front: `Media`

Smoke and gas are drawn as volumes, so a spark inside a cloud would draw over it. Every translucent world material is
dimmed instead by what gets through between the eye and its pixel, read from the world's media (`CgWorldMedia`, a
froxel grid the gas fills), as its blend takes it:

| Blend mode | Dimmed |
|---|---|
| `Additive`, `Subtract`, `AlphaComposite`, and the glow | the whole colour |
| `Translucent` | its opacity |
| anything else | nothing |

- `Material { Media Off }` draws over smoke undimmed: a HUD marker in the world.
  `cg_MediaTransmit(gl_FragCoord.xy / CG_RESOLUTION, cg_LinearEyeDepth(gl_FragCoord.z))` reads it by hand.
- **A volume writing `s.volumeDepth`** is never dimmed: it draws the smoke itself.
- The grid is 16 pixels a froxel across, so a dimmed edge is soft at that size.

#### What a post material reads of the scene: Unreal's SceneTextures

An opaque material may write Unreal's custom data (GBufferD) and its shading model beside its colour, and a post
material reads them back with the stencil and the world normal. There is no G-buffer: in a frame a post material reads
them, the world renderer draws the writers again (`CG_CUSTOM_DATA_TARGET`) beside the host's depth, so only what shows
lands.

```glsl
// an opaque surface: no-ops in every draw but that one
s.customData = vec4(outlineColour, outlineId);      // 0..1 each, stored as bytes
s.shadingModelId = uvec2(13u, outlineType);         // model 0..31, type 0..3

// a post material: naming one hands it all four
if (CG_CUSTOM_STENCIL(i.uv) != 1) discard;          // CustomStencil, 0..255 (Draw.customDepth)
vec3 n = CG_SCENE_WORLD_NORMAL(i.uv);               // world space; 0 where no draw of ours wrote one
vec4 data = CG_CUSTOM_DATA(i.uv);                   // as written
uvec2 model = CG_SHADING_MODEL_ID(i.uv);            // (model, type)
```

- **Post materials only** (`Location BEFORE_TRANSLUCENCY`), which the post stack binds from the firing; a firing that
  published none reads 0 throughout. Each read is the pixel at `uv`, unfiltered.
- `CG_CUSTOM_EYE_DEPTH` is not handed yet and fails to compile, naming the read.
- Host-drawn pixels (terrain, entities, the sky) have no normal or data, and read as DefaultLit (model 1), as a lit
  world pixel does in Unreal's GBuffer. Rebuild their normal from depth.
- An `Opaque` or `Masked` material writes the world normal for the world's lights without asking (`docs/LIGHTS.md` §7).
- `--mode=custom-depth` is the gate.

#### A second look on the same mesh

A material draws one look. A second look on the same mesh (an outline, a glow shell) is a second draw with its own
material, as Unreal draws a mesh's overlay material:

```java
CgWorldRenderer.get().draw(mesh, base).at(x, y, z).submit();
CgWorldRenderer.get().draw(mesh, outline).at(x, y, z).submit();   // its own material, the same mesh
```

A skinned instance names one for every slot: `CgAnimInstance.overlay(material)`.

---

### Loading and using a material

```java
CgMaterial mat = CgMaterial.load("mymod:shaders/terrain.shader");  // shared, cached per path: never changed

// Values of your own: an instance (they persist across frames until overwritten)
CgMaterialInstance mine = mat.instance().set1f("_Roughness", 0.8f).vec4("_Color", 1f, 0f, 0f, 1f);

// Keywords make other materials, cached:
CgMaterial mapped = mat.keyword("NORMAL_MAP", true);                // throws for an undeclared name
CgMaterialInstance mappedMine = mapped.instance();                   // values of your own on that variant
```

A material asset (a `.yaml` under `materials/`) names its shader and sets the same: [`vfx/materials.md`](vfx/materials.md).

### A material's state: drawing one shader several ways

A material may draw its shader under another blend, cull, depth write or clip than the shader's own: Unreal's material
instance overriding its parent's blend mode and two-sidedness.

```java
CgMaterial glow = CgMaterial.load("mymod:shaders/fx/soft.shader",
        CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).twoSided().build());
CgMaterial ink = CgMaterial.load("mymod:shaders/fx/ink.shader",
        CgMaterialState.builder().blend(CgBlendMode.ALPHA).clip().build());   // as ClipDepth, at its MaskClip
```

| `CgBlendMode` | Stands for |
|---|---|
| `OPAQUE` | `BlendMode Opaque` |
| `ALPHA` | `BlendMode Translucent` |
| `PREMULTIPLIED` | `BlendMode AlphaComposite` |
| `ADDITIVE` | `BlendMode Additive` |
| `MODULATE` | `BlendMode Modulate`: what is behind times the colour, fogged toward white |
| `SUBTRACT` | `BlendMode Subtract`: fogged and decoded as `ADDITIVE`, its glow added |

- **A blend or a clip is a program of its own** (`CgMaterialShaderRegistry.getOrCreate(path, state)`), compiled once,
  on first use. **Cull and depth write are pipeline state** on the shader's own programs (`CgMaterialState.onPass`).
- **A blend without `clip()` replaces a `ClipDepth` shader's whole**, as an instance's `bOverride_BlendMode` replaces its
  parent's Masked: it no longer cuts, and writes depth only when opaque.
- Refused at load: `depthWrite` on a `ClipDepth` or `DepthPrepass` shader (unless a blend without `clip()` dropped it)
  or with `clip()`; `clip()` on a `DepthPrepass` shader.
- **`disableDepthTest()`** (Unreal's `bDisableDepthTest`, a material asset's `DepthTest: false`) draws the colour, its glow and
  its bend over what is in front. Translucent only: refused with an opaque blend, a clip, a depth write, or on a
  `DepthPrepass` shader.
- **`separateTranslucency()`** (Unreal's separate translucency, the shader's `TranslucencyPass Separate`) draws a
  translucent draw after every other translucent draw, still depth-tested. Under the HDR scene it draws apart, into
  `CgSeparateTranslucency`, which the post stack lays over the scene after `BEFORE_SEPARATE_TRANSLUCENCY`.

### GLSL Standard Library

Located at `src/main/resources/assets/crystalgraphics/shaders/lib/`. Every file guards itself with `#pragma once`, so
two includes of it are one.

| File | Key functions |
|---|---|
| `math.glsl` | `saturate`, `remap`, `remap01`, `sq`/`cb`, `positive_pow`, `safe_pow`, `sign_pow`, `smootherstep`, `deg_to_rad`/`rad_to_deg` |
| `vector.glsl` | `safe_normalize`, `fresnel` (Schlick), `rotate_axis` (Rodrigues), `orthonormalize`, `project_onto`/`reject_from`, `angle_between` |
| `color.glsl` | `luminance` (BT.709), `srgb_to_linear`/`linear_to_srgb`, `fast_*` variants (Chilliant), `rgb_to_hsv`/`hsv_to_rgb` (branchless), `rotate_hue`, `desaturate` |
| `uv.glsl` | `rotate_uv`, `scale_uv`, `tile_uv`, `pan_uv`, `flip_uv_x/y`, `cartesian_to_polar_uv` |
| `noise.glsl` | `hash12`/`hash22`/`hash13` (sin-free), `value_noise`, `fbm4`/`fbm6`, `fbm(p, octaves)`, `fbm_ridged` |
| `sdf.glsl` | `sdf_rounded_box` (uniform / per-corner / elliptical), `sdf_segment`, `sdf_bezier` (exact quadratic, with a straight-line fallback), `sdf_coverage` (**fragment-only, guarded**) |
| `rng.glsl` | `cg_rng4(seed, element, step, stream)` (PCG4D, counter-based: any element draws its own numbers in any order), `cg_rng`, `cg_rng_unit` (the top 24 bits in [0, 1)), `cg_rng_unit4`, `cg_rng_direction`. Integer-exact on every tier; `compute.ops.CgRng` gives the same bits in Java |

The canvas's own (`texel`, pixel-art filtering; `stroke`, the one copy of a curve's caps and taper; `rect_blur`,
Skia's analytic rect blur; `text_gamma`, Skia's text gamma) are `canvas/primitives/`: what its primitives and coverage
passes read.

```glsl
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
```

#### Unreal's material expressions: `lib/material/`

One file an expression, each matching Unreal 4.21's (`MaterialExpressions.cpp`), so a game's material ported by hand
reads as its graph does. `--mode=material-library` checks each against Unreal's formula at every tier and on Vulkan.

| File | Function | Unreal |
|---|---|---|
| `depth_fade.glsl` | `cg_depth_fade(opacity, fadeDistance, sceneDepth, pixelDepth)`; in a fragment, `CG_DEPTH_FADE(opacity, fadeDistance, screenUv)` reads both depths itself | DepthFade |
| `panner.glsl` | `cg_panner(uv, time, speed, fractional)` | Panner |
| `rotator.glsl` | `cg_rotator(uv, center, time, speed)`: radians a second, counter-clockwise | Rotator |
| `particle_sub_uv.glsl` | `cg_particle_sub_uv(tex, coords0, coords1, subUVLerp, blend)`; on a particle factory, `CG_PARTICLE_SUB_UV(tex, cg_Particle, blend)` | ParticleSubUV |
| `rotate_about_axis.glsl` | `cg_rotate_about_axis(axisAndAngle, pivot, position)`: the offset to add, not the rotated position | RotateAboutAxis |

```glsl
#include "crystalgraphics:shaders/lib/material/depth_fade.glsl"
#include "crystalgraphics:shaders/lib/material/panner.glsl"

vec2 uv = cg_panner(i.uv0, CG_TIME, vec2(0.0, 1.5), true);
s.opacity = CG_DEPTH_FADE(texture(_Mask, uv).r, _FadeDistance, gl_FragCoord.xy / CG_RESOLUTION);
```

- Distances are blocks. A material value in a kit's own unit (Kakarot's centimetres) is converted where the asset
  loads; a constant compiled into a port's code is the port's to convert.
- `CG_DEPTH_FADE` and `CG_PARTICLE_SUB_UV` are macros: a material's `#include`s come before the engine's declarations,
  so a function reading those would not compile.
- RotateAboutAxis takes radians; Unreal's node takes periods, so a port multiplies by `2pi / Period` first.

#### Adding to the library

The library is where a technique lives once, for every material. A helper that any material could use belongs here,
not in the mod or content pack that first needed it: only what is one product's own look stays with that product.

1. **Look first.** Search `shaders/lib/` and the tables above for the technique under each of its names. Use what is
   there. The same name with other maths is not the same function: `fresnel()` in `vector.glsl` is not Unreal's Fresnel
   node, and a material matching Unreal takes the node's from `lib/material/`.
2. **Put it by what it is.**

   | It is | File | Name |
   |---|---|---|
   | An Unreal material expression | `lib/material/<node>.glsl` | `cg_<node>` |
   | An Unreal engine material function (`/Engine/Functions`) | `lib/material/functions/<name>.glsl` | `cg_<name>` |
   | A general technique | the library of its subject (`uv.glsl`, `color.glsl`, ...) or a file of its own in `lib/` | for the technique |

3. **Write it to these rules.**
   - `lib/material/` is exact: Unreal's clamps, thresholds and input names, read from its implementation, and the
     header comment names the class it matches. Unreal's code is read, never copied.
   - It names no product: no game, mod or studio in a name or a comment, and none of its constants inside. They arrive
     as arguments.
   - It is pure in what a material declares: a property, a factory's parameters (`cg_Particle`) and a collection value
     are arguments, since the file is compiled into materials that declare none of them.
   - Distances are blocks and angles radians. `#pragma once` first. Derivatives sit under
     `#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)`.
4. **Prove and document it**: its check in `--mode=material-library`, and its row in the table above.

**Package guides for this layer**: `api/material/CLAUDE.md` · `render/CLAUDE.md` · `render/world/CLAUDE.md` · `render/stage` (its classes' javadoc) · `material/CLAUDE.md` · `material/shader/CLAUDE.md`

### No raw shaders

There is no API that compiles GLSL text: a full-screen pass is a post material (`CgPostMaterial`) or a raster pass
drawing a material, and GPU work that is not drawing is a kernel. Both get `cg_env.glsl`, and either may include the
standard library.

**Package guides**: `api/shader/CLAUDE.md` · `material/CLAUDE.md` · `mc/shader/CLAUDE.md`

## Compute — `.compute` kernels

A `.compute` holds **kernels**: GLSL functions the GPU runs once per element, as a compute shader on GL 4.3 and up
and on the Vulkan device, with the same answer on each ([Where kernels run](#where-kernels-run)).
**`crystalgraphics:shaders/reference/kernel.compute` is the reference**: every part of the format, annotated, compiled by the
tests on every target. Files live under `shaders/`, beside the `.shader` that draws what they write. Designing one:
[Designing a kernel](#designing-a-kernel). Plan:
`plan/crystalgraphics/gpu-compute.md`; the package's internals: `compute/CLAUDE.md`. The VFX particle kernels are
generated from emitters' module stacks, with no `.compute` file: a particle behaviour is a module
([`VFX.md`](VFX.md) § *Writing a particle module of your own*), not a kernel.

### Start to finish

A kernel stepping sparks, and a quad drawn for each one still alive:

```glsl
// mymod:shaders/sparks.compute. No #version and no main(): the compiler writes both, per kernel and device.
#pragma kernel Step map                 // a name, the work group's size (none: 64), a shape

Properties {
    _Step ("Time step", float) = 0.05
    _Drag ("Drag",      float) = 0.5
}

struct Spark { vec4 positionLife; vec4 velocitySeed; };   // declared before Buffers { }

Buffers {
    IN    ("Sparks",       Spark, readonly)
    OUT   ("Sparks after", Spark, writeonly)
    ALIVE ("Alive",        uint,  writeonly)
}

void Step() {
    Spark s = IN(CG_ELEMENT);                                // this invocation's element
    s.velocitySeed.xyz = s.velocitySeed.xyz * (1.0 - _Drag * _Step) + vec3(0.0, -9.81, 0.0) * _Step;
    s.positionLife += vec4(s.velocitySeed.xyz * _Step, -_Step);
    OUT_WRITE(s);                                            // this element of OUT
    ALIVE_WRITE(s.positionLife.w > 0.0 ? 1u : 0u);
}
```

```java
CgKernel step = CgCompute.load("mymod:shaders/sparks.compute").kernel("Step");   // parsed once; any thread
CgGraphBuffer sparks = CgGraphBuffer.history("sparks", CgBufferDesc.elements(CAPACITY, 32, CgBufferUsage.STORAGE));
CgGraphBuffer alive = CgGraphBuffer.transientBuffer("sparks.alive", CgBufferDesc.elements(CAPACITY, 4, CgBufferUsage.STORAGE));
CgGraphBuffer live = CgGraphBuffer.persistent("sparks.live", CgBufferDesc.elements(CAPACITY, 4, CgBufferUsage.STORAGE));
CgGraphBuffer count = CgGraphBuffer.persistent("sparks.count", CgBufferDesc.of(16, CgBufferUsage.STORAGE));

// Stepped once a frame, in the opaque stage's first firing, ahead of the world renderer, whose draws read the list.
CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(CgWorldRenderer.ORDER - 1, frame -> {
    CgComputePass pass = frame.recording().compute("sparks.step");
    pass.dispatch(step, CAPACITY).bind("IN", sparks).bind("OUT", sparks).bind("ALIVE", alive).set("_Step", dt);
    CgGpuOps.compact(pass, alive, null, CgGpuCount.of(CAPACITY), live, count, 0);   // live indices, and how many
    pass.end();
});

sparkMaterial.buffer("SPARKS", sparks).buffer("LIVE", live);   // what the material's Buffers { } reads
world.draw(CgMesh.quads(CAPACITY), sparkMaterial)
     .indirect(count, 0, CgIndirect.INDICES, 6)              // six indices per spark the GPU counted
     .at(x, y, z).bounds(box).submit();
```

- **Nothing above names a device.** On GL and the Vulkan device alike `Step` and the compaction run as compute
  shaders, and the draw takes the count the GPU wrote.
- **A history buffer is the simulation's state**: `IN` reads the newest version and `OUT` writes the next, so one
  buffer is both without a race. `sparks.previous()` is the version before.
- **The live list and its count are persistent**: the draw may execute in another stage's frame (a transparent
  material draws in `WORLD_TRANSPARENT`) or in a later firing of this one, and a transient lasts one firing.
- **Stepped once a frame**: `registerOncePerFrame` records on the stage's first firing of each host frame, so a second
  firing (1.7.10's anaglyph, a portal mod drawing the world again) draws the same sparks rather than stepping them
  twice. Work that depends on the view, culling or sorting by depth, registers per firing with `register`.
- **The material reads the sparks** through its own `Buffers { }`, quad n placed at spark `SPARKS(LIVE(n))`: the
  material is [Reading a kernel's buffers](#reading-a-kernels-buffers)'s example.

### The file

#### `#pragma kernel`, and the shape

```glsl
#pragma kernel Name [x [y [z]]] shape    // sizes: the work group's; none is 64 in one dimension
#pragma kernel Integrate map             // 1D, 64 a group
#pragma kernel Bin 256 scatter           // 1D, 256 a group
#pragma kernel Shade 8 8 image           // 2D: CG_TEXEL.xy is the texel, dispatched (width, height, 1)
```

The kernel is `void Name()` somewhere in the file, taking and answering nothing. **The shape says what it writes**,
and so what it may reach; the compiler refuses a kernel that reaches past its shape, naming the kernel, what it
reached and the line, helpers and macros included.

| Shape | Writes | Gets |
|---|---|---|
| `map` | its own element of each output buffer | `NAME_WRITE(v)` |
| `gather` | as `map`, reading other elements too | as `map` |
| `append` | as `map`, and appends elements | `NAME_APPEND(v)` |
| `scatter` | at computed indices: stores, adds, minima, maxima, counters | `NAME_STORE`, `_ADD`, `_MIN`, `_MAX`, `_INC` |
| `image` | its own texel of each output image | an image's `NAME_WRITE(v)` |
| `general` | anything compute does: `shared` memory, `barrier()`, subgroups, raw atomics, any index of any image | everything, and `NAME_DATA[i]` |

Only a `general` kernel may name `barrier()` and the memory barriers, `atomic*`, `imageLoad`/`imageStore`/`imageSize`,
`subgroup*` and `CG_SUBGROUP_*`, `CG_ATOMIC_*`, `shared` variables, or anything of a work group (`gl_LocalInvocationID`,
`CG_GROUP_ID`, `CG_LOCAL_INDEX`, …): the narrower shapes say what a kernel does, and the compiler holds it to that.

#### Keywords, engine buffers, includes

```glsl
#pragma cg_feature WIND                  // a keyword: each set asked for compiles once, as a material's variants
#pragma cg_use particle                  // an engine buffer: writable in a general kernel, read-only in the rest
#include "crystalgraphics:shaders/lib/rng.glsl"
```

- `cg_feature` (at most 8), `cg_use` and `#include` work as in a `.shader`; any `lib/` file may be included.

#### `Properties`

```glsl
Properties {
    _Gravity  ("Gravity",   vec4)      = (0, -9.81, 0, 0)
    _Drag     ("Drag",      float)     = 0.1
    _BinCount ("Bins",      int)       = 64
    _Noise    ("Noise",     sampler2D) = "white"
}
```

Values sit in one std140 block (`CgKernelBlock`) and samplers on texture units of their own. A material's types;
**`vec3` is refused**, since std140 pads it. A dispatch sets them (`dispatch.set("_Drag", 0.2f)`,
`dispatch.texture("_Noise", tex)`) and anything unset keeps the file's default.

#### `Buffers`

```glsl
struct Particle { vec4 positionLife; vec4 velocitySeed; };   // a struct a buffer holds comes before Buffers { }

Buffers {
//  NAME     ("Display",        element,  access)
    STATE   ("Particle state", Particle, readwrite)
    SPAWNED ("Spawned",        Particle, append)
    BINS    ("Height counts",  uint,     counter)
    WEIGHTS ("Height weights", float,    readwrite)
    SUMS    ("Life per group", float,    writeonly)
}
```

The engine writes each buffer's declaration and accessors, and buffer `i` is bound at storage binding point `i`;
**the code never declares a `buffer` block, a `uniform` or a `binding =`**. An element is a scalar, vector, matrix or a
struct declared above, its arrays sized by integers. **Below `general`, an element is a `float`, `int` or `uint`,
their 2- and 4-vectors, or a struct of `vec4`, `ivec4` and `uvec4` only**: what a context without compute can hold.
A `general` kernel keeps any std430 layout.

| Access | Accessors | Notes |
|---|---|---|
| `readonly` | `NAME(i)` | |
| `writeonly` | `NAME_WRITE(v)`, `NAME_STORE(i, v)` | |
| `readwrite` | `NAME(i)`, `NAME_WRITE(v)`, `NAME_STORE(i, v)`, `NAME_ADD`/`_MIN`/`_MAX(i, v)` | the atomics on a `float`, `int` or `uint` element |
| `append` | `NAME(i)`, `NAME_APPEND(v)`, `NAME_COUNT()` | the count is a separate `uint` the caller binds and zeroes; `NAME_APPEND` answers the element it took (-1 when full) |
| `counter` | `NAME(i)`, `NAME_INC(i)`, `NAME_ADD(i, n)` | `uint` or `int`; each answers the value before |

Every buffer has `NAME_LENGTH()`, and a `general` kernel `NAME_DATA[i]`, the array itself. Which accessor a shape
gets: `NAME_WRITE` in every shape but `image`; `NAME_STORE`, `_ADD`, `_MIN`, `_MAX` and `_INC` in `scatter` and
`general`; `NAME_APPEND` in `append` and `general`. A float add, minimum or maximum is native where the device has
float atomics and a compare-and-swap loop where not. `NAME_APPEND` past the buffer's length is dropped, and
`NAME_COUNT()` never answers more than the length.

#### `Images`

```glsl
Images {
//  NAME     ("Display", format, access [, 2d | 3d | 2darray | cube])
    SOURCE  ("Source",  rgba8,  readonly)
    HEAT    ("Heat",    rgba8,  writeonly)
    DENSITY ("Density", r32ui,  readwrite, 3d)
}
```

Image `i` is bound at image unit `i`, so a file declares at most 8 images (units 0 to 7, GL's guaranteed minimum);
kernels writing alike share one, each dispatch binding its own texture to it. The texture bound must have the declared
format. Formats are GLSL's layout
qualifiers: `rgba32f`, `rgba16f`, `rg32f`, `rg16f`, `r11f_g11f_b10f`, `r32f`, `r16f`, `rgba16`, `rgb10_a2`, `rgba8`,
`rg16`, `rg8`, `r16`, `r8`, the `_snorm` forms of the 16- and 8-bit ones, and the `i` and `ui` integer formats.

| Accessor | Is | Who |
|---|---|---|
| `NAME_LOAD(p)` | the texel at `p` (`ivec2`, or `ivec3` for 3D, an array or a cube) | `readonly`, `readwrite` |
| `NAME_SIZE()` | the bound level's size | any |
| `NAME_WRITE(v)` | this texel, `CG_TEXEL` | `image`, `general` |
| `NAME_STORE(p, v)` | any texel | `general` |
| `NAME_ADD`/`_MIN`/`_MAX(p, v)` | an atomic, on `r32i` or `r32ui`, `readwrite` | `general` |

A texel reads and writes as `vec4`, `ivec4` or `uvec4` by its format. A `3d` image binds a volume of the frame graph
and a `2d` one any other texture, or the dispatch throws ([Volumes](#volumes)).

#### Everything else

Constants, structs and functions any kernel may call; `shared` variables, one a statement, for `general` kernels.
**Each kernel's source carries only what it reaches**: a helper using what a `map` may not costs a `map` kernel
nothing until it calls it. The file never declares `#version`, `main()`, `layout(local_size_*)`, a `uniform`, a
`buffer` or an `image`; each is refused with where it belongs.

### Built-ins

| Name | Is |
|---|---|
| `CG_ELEMENT` | this invocation's element, x fastest: `x + w * (y + h * z)` |
| `CG_DISPATCH_ID`, `CG_DISPATCH_COUNT` | `ivec3`: where it is, and the elements asked for per axis (an indirect dispatch: its groups times their size). Read these, never `gl_GlobalInvocationID` |
| `CG_IN_RANGE` | whether this invocation is one of the elements asked for |
| `CG_TEXEL` | the texel an `image` kernel writes; `.xy` for a 2D image |
| `CG_GROUP_ID`, `CG_LOCAL_ID`, `CG_LOCAL_INDEX` | the work group, in a `general` kernel only |
| `CG_LOCAL_SIZE_X`/`_Y`/`_Z`, `CG_LOCAL_SIZE`, `CG_GROUP_SIZE`, `CG_GROUP_POW2` | the group's size per axis, as `ivec3`, in all, and that rounded up to a power of two (a reduction's tree) |
| `CG_DIMENSIONS`, `CG_KERNEL_<Name>` | how many sizes the pragma gave; defined in the kernel being compiled, for code shared by several |
| `CG_TIME`, `cg_ViewMatrix`, `cg_ProjMatrix`, `CG_RESOLUTION`, … | `cg_env.glsl`'s frame block, from the pass's constants (identity matrices and the time now for a pass given none) |
| `CG_SUBGROUP_*` | subgroup operations, `general` only (below) |
| `CG_ATOMIC_ADD_FLOAT`/`_MIN_`/`_MAX_(mem, v)` | float atomics on a `uint` holding a float's bits, in a buffer or `shared`, `general` only: `shared uint total; CG_ATOMIC_ADD_FLOAT(total, w);` |

**Every shape but `general` returns before the kernel runs for an invocation past the count**; a `general` kernel runs
every invocation of every group, so its barriers stay uniform, and asks `CG_IN_RANGE` itself, after them.

**Subgroups** are the device's operations where it has all of basic, vote, arithmetic, ballot and shuffle, and the
whole work group through shared memory where not; a kernel written against them runs on both and never branches on
support:

```glsl
shared float partial[CG_GROUP_SIZE];

void Reduce() {                                              // #pragma kernel Reduce 256 general
    float v = CG_IN_RANGE ? VALUES(CG_ELEMENT) : 0.0;
    float sum = CG_SUBGROUP_ADD(v);
    if (CG_SUBGROUP_ELECT()) partial[CG_SUBGROUP_INDEX] = sum;   // one per subgroup: CG_SUBGROUP_COUNT of them
    memoryBarrierShared();
    barrier();
    if (CG_LOCAL_INDEX == 0) {
        float total = 0.0;
        for (int i = 0; i < CG_SUBGROUP_COUNT; i++) total += partial[i];
        SUMS_STORE(CG_GROUP_ID.x, total);
    }
}
```

`CG_SUBGROUP_SIZE`, `_INVOCATION`, `_INDEX`, `_COUNT`; `CG_SUBGROUP_ADD`, `_MIN`, `_MAX`, `_INCLUSIVE_ADD`,
`_EXCLUSIVE_ADD(x)`; `_ALL`, `_ANY`, `_BALLOT`, `_BALLOT_COUNT(b)`; `_BROADCAST(x, id)`, `_FIRST(x)`, `_ELECT()`,
`_BARRIER()`. Call them where the whole work group does, on `float`, `int` or `uint` scalars. Emulated, the work group
is the subgroup (`CG_SUBGROUP_COUNT` 1), it costs `(CG_GROUP_SIZE + 4) * 4` bytes of shared memory, and a ballot holds
128 invocations: a larger group calling `CG_SUBGROUP_BALLOT` is refused where subgroups are emulated.

### Running a kernel

#### In a frame graph

```java
CgComputePass sim = recording.compute("particles.step", constants);   // constants: the frame block; omit for none
sim.dispatch(simulate, count)                       // count elements; (kernel, x, y, z) for 2D and 3D
    .bind("STATE_IN", state)                        // a history: read as its newest version
    .bind("STATE_OUT", state)                       // ... and written as its next
    .bind("SPAWNS", spawns).counter("SPAWNS", spawnCount, 0)   // an append buffer's count: a uint at that offset
    .image("DENSITY", density)                      // a graph texture as a storage image; (name, tex, level, layer)
    .texture("_Noise", noise)                       // a sampler property
    .set("_Drag", 0.1f);                            // a value property: float, vec2, vec4 or int
sim.dispatchIndirect(spawn, spawnArgs, 0).bind("SPAWNS", spawns);   // group counts from three uints on the GPU
sim.end();
```

- **What a binding is read or written as comes from the kernel's accessors**: `STATE(i)` reads, `STATE_WRITE` writes.
  The graph orders passes and places every barrier from that; **every buffer and image a kernel uses must be bound**.
- **Record a kernel ahead of the draw that reads what it writes.** In a world stage that is a renderer registered
  below `CgWorldRenderer.ORDER`; one that advances state registers with `registerOncePerFrame`.
- `dispatchGroups(kernel, x, y, z)` dispatches whole groups: `CG_DISPATCH_COUNT` is every invocation they hold. A
  count past the device's group limit runs as several dispatches, each from its own base.
- A pass is culled when nothing reads what it writes, unless it writes a persistent, history or imported buffer, or a
  texture that outlives the frame. A frame executed again does not run it again unless it writes only transients or is
  marked `again()`: a frame shown twice must not step a simulation twice.

| Graph buffer | Storage | |
|---|---|---|
| `CgGraphBuffer.transientBuffer(name, desc)` | this frame only, pooled by size | scratch between passes |
| `CgGraphBuffer.persistent(name, desc)` | made on first use, kept until `recording.release(buffer)` | state, counts read by a later stage |
| `CgGraphBuffer.history(name, desc)` | two, the newest versions; each write makes the next | a simulation: read one, write the other |
| `CgGraphBuffer.imported(name, glBuffer, bytes)` | someone else's GL buffer | interop |

```java
CgBufferDesc desc = CgBufferDesc.elements(count, 32, CgBufferUsage.STORAGE);   // or CgBufferDesc.of(bytes, usage, ...)
recording.fill(buffer, 0);                                   // every word; (buffer, offset, size, value) for a range
recording.update(buffer, 0, bytes);                          // a ByteBuffer, copied now
recording.copy(from, 0, to, 0, size);
recording.release(buffer);                                   // a persistent or history buffer, once its users ran
state = recording.resize(state, CgBufferDesc.elements(capacity, 32, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
```

`resize` answers a new handle of the buffer's kind holding what each version held, up to the smaller size, and releases
the old one: use the new handle from then on, and bind it again wherever the old one was bound (`material.buffer`).

A graph texture's region is written the same way, its bytes copied when recorded, rows bottom first and tightly packed
in the texture type, as a texture readback answers them:

```java
recording.update(heights, 0, 32, 0, 16, 16, slab);          // level 0, a 16x16 region at (32, 0); the rest kept
```

#### Volumes

A 3D graph texture: written by kernels that declare it a `3d` image, sampled by kernels and materials as a `sampler3D`,
updated and read back by boxes, slices from `z` up, each laid out as a region is. The voxels of a world window, a gas
grid, a vector field.

```glsl
Properties { _Wind ("Wind", sampler3D) = "black" }
Images { VOXELS ("Voxels", r8ui, writeonly, 3d) }
// in an image kernel dispatched (w, h, d): CG_TEXEL.xyz is the texel, VOXELS_SIZE() an ivec3
// anywhere: textureLod(_Wind, uvw, 0.0) filters across slices as within one
```

```java
CgGraphTexture voxels = CgGraphTexture.requested("voxels", CgTextureDesc.volume(128, 96, 128, CgFormat.R8_UINT));
pass.dispatch(fill, 128, 96, 128).image("VOXELS", voxels);
step.dispatch(move, n).texture("_Wind", wind);
recording.update(voxels, 0, 0, 0, 32, 128, 96, 16, slab);   // 16 new slices from z 32: 128 * 96 * 16 bytes
CgMaterialBindings reads = CgMaterialProxy.of(material).bindings().with(0, voxels);   // a material's sampler3D
```

- One level and one colour attachment; nothing draws into a volume, so a raster pass into it throws. A volume of your
  own, with its sampling and mip levels (`CgTextures.volume(name, desc)`), is imported through
  `CgGraphTexture.imported(name, CgTextures.target(name, volume, null))`, a kernel writing each level as
  `image(name, volume, level, -1)`.
- Its format's type is its filter: linear, nearest for an integer type, clamped at every face.
- Below compute an `image` kernel writes a volume in one draw, an instance a slice placed by a geometry stage's
  `gl_Layer`, and one loading the volume it writes is refused.
- In `textureLod` from a kernel, give the level: a kernel run as compute has no derivatives.

A buffer declares every use it is put to: `STORAGE` for a kernel or a storage block, `INDIRECT` for
`dispatchIndirect`'s arguments, `COPY` for a fill, update or copy; `VERTEX`, `INDEX` and `UNIFORM` for draws. **New
storage holds whatever the driver gives**: fill it, or write every element, before anything reads it.

#### Outside a frame

```java
CgRecording rec = new CgRecording();                             // a graph of one compute pass, executed now
CgComputePass run = rec.compute("bake");
run.dispatch(bake, count).bind("CELLS", CgGraphBuffer.imported("cells", glBuffer, bytes));
run.end();
CgRenderer.executeNow(rec);
```

`CgKernelProgram` is the direct form, dispatched on a device compute pass of the caller's (`dispatchOn`): it orders
nothing after it, so the caller states the reader's barrier (`CgCommandList.bufferBarrier`).

#### Before the first dispatch

A kernel compiles at its first dispatch, on the render thread. `precache()` starts it earlier — at load, on a loading
screen — so that frame finds it compiled:

```java
CgCompute particles = CgCompute.load("mymod:shaders/particles.compute");
particles.kernel("Simulate").precache();                 // each kernel and keyword set the effect dispatches
particles.kernel("Simulate").withKeywords("WIND").precache();
```

- The compile runs on a job worker (`CgShaderCompile`) while frames go on; the dispatch that takes the program waits
  for what is left (`compute.compileWait`, counted by `compute.compile-waits`). Before the toolchain lands, a GL-era
  host's native compiler compiles at that first ask, on the render thread.
- Once compiled, a frame boundary makes it ready (`compute.finishPrecached`, about 1 ms a frame at most).
  `kernel.isReady()` answers true only once that is done, so a precached kernel a pool waits for never finishes at its
  dispatch. A material's commit and its variants keep the same rule.
- Every host keeps shaderc's output across launches (`CgCacheDirectory`, `crystalgraphics/cache/spirv` under the game
  directory), and with it the Vulkan pipeline cache or the GL device's linked programs (`glprogram`): a second launch
  compiles roughly half as long on Vulkan; on GL (Minecraft 1.20.1's dev run) it took the loading screen from about 25 s
  to 12.

### Drawing what a kernel wrote

The whole workflow, from a kernel to culled draws joined into one call, with recipes and the design rules:
[`GPU_DRIVEN_RENDERING.md`](GPU_DRIVEN_RENDERING.md).

| A kernel wrote | A draw reads it |
|---|---|
| a count | `.indirect(count, offset, mode, factor)` on a world or chunk draw |
| an image | the graph texture, sampled by a material; a volume as a `sampler3D` |
| a buffer of records | `NAME(i)` in a material declaring it in `Buffers { }`, bound with `material.buffer(name, buffer)` |
| object records, `CgInstanceKind.OBJECT`'s 48 floats each | `.objects(records, first, n)` on a chunk draw: instance i reads record `first + i` through `CG_OBJECT_DATA`, in any material; or `.instances(records, count)` on a world draw, culled on the GPU (below) |

```java
// A count: a quad per live spark, or a mesh instanced once per element
world.draw(CgMesh.quads(capacity), sparks).indirect(alive, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
world.draw(billow, smoke).indirect(alive, 0, CgIndirect.INSTANCES, 1).at(x, y, z).bounds(box).submit();

// Object records: a rock per record a cull kept, as many as it counted
chunks.draw(rocks.pipeline(CgInstanceKind.OBJECT), bindings, rock).objects(visible, 0, capacity)
      .indirect(visibleCount, 0, CgIndirect.INSTANCES, 1);

// An image: written by a kernel, sampled by the material's first sampler in a pass recorded after it
CgGraphTexture heat = CgGraphTexture.transientTexture("heat", CgTextureDesc.of(256, 256, CgFormat.RGBA8_UNORM));
CgComputePass shade = rec.compute("heat");
shade.dispatch(heatKernel, 256, 256, 1).image("HEAT", heat);
shade.end();
CgMaterialBindings bindings = CgMaterialProxy.of(material).bindings().with(0, heat);
```

- `INDICES` and `VERTICES` draw count × factor of the mesh's range, never more than it holds; `INSTANCES` draws the
  range count × factor times, each instance reading the draw's one object record, and `CG_DRAW_INSTANCE` is which
  element it is. On a draw of `objects(records, first, n)` instance i reads record `first + i`, and the count draws no more than
  n.
- **A buffer of records** is [Reading a kernel's buffers](#reading-a-kernels-buffers): the material declares it as the
  kernel does, `readonly`, and the graph orders the draw after the pass writing it.

### Reading what a kernel wrote, on the CPU

```java
CgRequest got = recording.readback(counts, 0, 4, data -> alive = data.getInt(0));   // after the pass writing it
recording.readback(heat, 0, 0, 0, 64, 64, data -> data.asFloatBuffer().get(heights));   // texture, level, region
recording.readback(voxels, 0, 0, 0, 40, 128, 96, 2, data -> check(data));   // a volume's box: slices 40 and 41

// An event stream a kernel appended to: the rows its count says were written, with the count
CgGpuOps.readRows(recording, landings, 0, 16, CgGpuCount.at(counts, 2, MAX_LANDINGS), (count, rows) -> {
    for (int at = 0; at < rows.limit(); at += 16) dust(rows.getFloat(at), rows.getFloat(at + 4), rows.getFloat(at + 8));
});
```

- **It never stalls**: the GPU copies into memory the CPU maps, and the sink runs on the render thread once the GPU has
  finished, from `CgGraphicsLifecycle.tickFrame`: usually a frame or two later. Steer by what arrived, not by what was
  asked this frame. The request is done once the sink has run, and failed if the context goes first.
- `data` is valid only during the call, in native byte order; a texture region is its rows bottom first, tightly
  packed, in the texture type's base format and pixel type (`CgReadback.pixelBytes`).
- A buffer needs `COPY`; a texture is read from its first colour attachment.
- **Rows with their count** (`CgGpuOps.readRows`): the sink gets the count as the GPU wrote it, which may pass the
  capacity, and at most the capacity's rows (`rows.limit()` is their bytes). The capacity's rows are copied whatever
  the count, so size the capacity to the stream.
- Outside a graph, `CgReadback.buffer(buffer, offset, size, sink)` and `CgReadback.texture(texture, level, x, y, w,
  h, sink)` do the same on a buffer or a texture of yours.

### Beside the drawing: `async()`

```java
// A simulation stepped at the opaque stage and drawn at the transparent one: the world draws beside it.
CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(order, frame -> {
    CgComputePass step = frame.recording().compute("sparks.step").async();
    step.dispatch(simulate, capacity).bind("IN", sparks).bind("OUT", sparks);
    step.end();
});

// A cull ahead of a shadow map that touches neither buffer: drawn while the cull runs, wherever recorded.
CgComputePass cull = recording.compute("instances.cull", constants).async();
cull.dispatch(cullKernel, count).bind("INSTANCES", instances).counter("VISIBLE", visible, 0);
cull.end();
recording.raster("mymod.shadow").depth(shadowMap, CgLoad.CLEAR_DEPTH, CgDepthAccess.WRITE) ...;
```

**Mark every pass that fits.** `async()` is opt-in, not optional: a pass that fits and is left in order costs the
frame its whole time, where async it costs little or nothing. A pass fits when both hold:

1. **It is big enough to hide**: thousands of elements, or dispatches with barriers between them (a simulation step,
   a sort, a scan, a cull, a reduction's last levels). A pass of a few hundred elements costs more to hand between
   queues than it hides.
2. **Drawing runs between it and its first reader** in the frame: a step at the opaque stage drawn at the transparent
   one, a cull or sort recorded ahead of passes that do not use it. Recording order and stage boundaries do not
   matter (below); only what the frame does in between.

Leave it off a small pass, a pass whose reader is the next thing the frame does, and heavy compute beside heavy
compute, which only fight for the same units.

- **Where the device has a compute queue** (`CgCapabilities.asyncCompute()`: the owned Vulkan device; not Minecraft's,
  where it could wait only on Minecraft's end-of-frame submit), the pass runs on it, after every step placed before it.
  The steps after it that touch nothing it reads or writes run beside it, and the first that does waits for it, in
  this stage or a later one of the frame; a callback waits for all of it.
- **Its waits cross stages for what only the graph reaches**: graph buffers (not imported ones) and transient
  textures. An imported, current or requested texture or an imported buffer the host may touch outside the graph, so
  work on one is waited for at the end of its stage. The frame's end waits for everything.
- **The builder places it, not the recording**: the pass and what it reads go as early as the graph allows, and what
  reads its results as late, so the drawing recorded after its consumer still runs beside it. On every device: the
  order is one the reads and writes allow, so the result is the same.
- **Everywhere else it runs in order**, with the same result: GL, and a Minecraft device with no compute queue of its
  own.
- **On Minecraft's device an async pass may not use Minecraft's own textures** (its main target, the lightmap): only
  Minecraft's graphics queue may, and the pass throws naming the texture.
- `--mode=async-compute` measures it: beside eight 1080p blurs (2.5 ms), half of 1 ms of fill-bound drawing disappears
  on an RTX 4070 SUPER; a sort in one stage with the drawing in the next overlaps the same way.
- `-Dcrystalgraphics.graph.asyncAll=true` sends every pass that can go async, which is how the gates check the waits.

### Where kernels run

| Tier | Context | A kernel |
|---|---|---|
| `V` | CrystalGraphics' Vulkan device | runs as compute |
| `G43` | GL 4.3, or 4.2 with the compute and storage-image extensions | runs as compute |
| `G40`, `G33` | GL 4.0–4.2 without compute (macOS's 4.1), GL 3.3 | is refused where it is recorded, naming the tier |

The engine's floor is GL 4.6 and Vulkan, so every supported context runs compute. `G40` and `G33` are still detected;
the form that ran kernels there as raster passes was removed, and `docs/LOWERED_COMPUTE.md` says how to bring it back.
`-Dcrystalgraphics.compute.tier=V|G43` forces a tier; forcing one the context lacks throws naming what it lacks.

### Ops: `CgGpuOps`

What every GPU-driven consumer would otherwise write: dispatched into the caller's compute pass. Scratch is graph transients from the pass's recording (`CgRecording.scratch`), the same handles each frame
a recording is reused, so a stage's ops make none after its first frame.

```java
CgComputePass pass = recording.compute("particles");
pass.dispatch(simulate, capacity).bind("STATE", state).bind("ALIVE", flags);
CgGpuOps.compact(pass, flags, null, CgGpuCount.of(capacity), live, counts, 0);   // the live indices, in order
CgGpuCount alive = CgGpuCount.at(counts, 0, capacity);                         // how many, as the GPU counted
CgGpuOps.sort(pass, Element.FLOAT, Order.DESCENDING, depths, live, alive);    // back to front, stable
CgGpuOps.dispatchArgs(pass, alive, 64, args, 0);                              // for dispatchIndirect
pass.end();
```

| Op | Does |
|---|---|
| `fill(pass, buffer, value, count)` | the count's words set to `value` |
| `iota(pass, buffer, first, step, count)` | word i is `first + i * step` |
| `copy(pass, from, to, count)` | the count's words copied |
| `dispatchArgs(pass, count, groupSize, args, word)` | the three group counts `dispatchIndirect` reads; `args` needs `INDIRECT` |
| `reduce(pass, fold, element, values, count, result, word)` | `SUM`, `MIN` or `MAX` of the count's elements; the identity for none |
| `bounds(pass, records, stride, offset, count, out, word)` | the box of the points at `offset` of each record: min xyz then max xyz |
| `scan(pass, scan, fold, element, values, count, out)` | `INCLUSIVE` or `EXCLUSIVE` prefix fold |
| `compact(pass, flags, values, count, out, outCount, word)` | the indices of non-zero flags in order (or their `values`), and how many |
| `expand(pass, lengths, rows, out, total, word)` | row r's `lengths[r]` elements, one after another: each a `uvec2` of its row and its index in it, and how many. What a kernel claiming k slots a source writes before a map fills them |
| `sort(pass, element, order, keys, values, count)` | LSD radix sort, stable, moving `values` with the keys when given |
| `sort(pass, bits, order, keys, values, count)` | by the low `bits` bits of `uint` keys: a 12-bit cell id in three passes, not eight |
| `histogram(pass, keys, count, bins, binCount, shift)` | bin `min(key >>> shift, binCount - 1)` counted; exact to 2^24 a bin |
| `downsample(pass, texture, filter)`, `(pass, texture, from, to, filter)` | each mip level from the one above: `AVERAGE` (area-weighted), `MIN` or `MAX` (a depth pyramid) |
| `blur(pass, source, target, sigma)`, `(pass, source, level, target, level, sigma)` | a separable Gaussian; in place at a small level is the cheap blur |
| `blur(pass, source, x, y, w, h, target, tx, ty, sigmaX, sigmaY, edge)` | a region blurred into place, a sigma an axis, taps past it held (`CLAMP`) or folded back in (`MIRROR`) |
| `shrink(pass, source, x, y, w, h, target, tx, ty, tw, th)` | a region, fractional, shrunk into a smaller rect: each target texel the average of the source area it covers (an area resample: Pillow's `BOX`, OpenCV's `INTER_AREA`). A thumbnail |
| `blurScaled(pass, source, x, y, w, h, target, sigma, edge)` | Skia's scale-then-blur: the region box-reduced by 1, 2 or 4 (the target's size says which; `blurScale(sigma)` picks it), then blurred there |
| `depthPyramid(recording, depth, constants, pyramid)` | a depth texture (or a target lending one) as eye depth, each level the farthest it covers: what a cull tests against |
| `cull(pass, cull, instances, [first,] count, out, counts, word)` | instances of one mesh culled as `CgWorldRenderer` culls a draw (frustum, level by screen height, the pyramid's depth): each level's kept object records, and how many; from record `first` for one range of a shared buffer |
| `cull(pass, view, sets, out, counts)` | every set of a `CgCullSets` culled at once, each as the op above with order kept: two dispatches per instance buffer and one sort, however many sets |

- **A count is fixed or a word on the GPU** (`CgGpuCount.of(n)`, `CgGpuCount.at(buffer, word, capacity)`). A GPU count
  dispatches the capacity and every kernel stops at the count it reads, so no op needs it on the CPU.
- Buffers hold 32-bit words with `STORAGE`; an `Element` (`UINT`, `INT`, `FLOAT`) says how to read them. Nothing past
  the count is written, and an op handed one buffer to read and write throws.
- **The same bits on every device**: integers exactly, a float sum in the same tree order (sixteen at a time, so not
  a plain loop's bits), a sort stable everywhere. The sort is FidelityFX Parallel Sort's, and a histogram of at most
  1024 bins counts in shared memory.
- **The image ops** take graph textures of `CgGpuOps.IMAGE_TYPES` (RGBA8, RGBA16F, R16F, R32F), with levels for a
  chain (`CgTextureDesc.withMips()`), and answer within a rounding of the format. A blur reads
  `2 * ceil(3 * sigma) + 1` texels an axis, so a wide one belongs at a smaller level.

```java
CgGraphTexture bloom = CgGraphTexture.transientTexture("bloom", new CgTextureDesc(w, h, HDR).withMips());
CgGpuOps.downsample(pass, bloom, Filter.AVERAGE);            // each level from the one above
CgGpuOps.blur(pass, bloom, 3, bloom, 3, 2f);                 // in place, at an eighth the size
CgGpuOps.downsample(pass, depth, Filter.MAX);                // a depth pyramid: each texel the farthest it covers
```

**Culling a set of instances** (`CgCull`): records in `CgInstanceKind.OBJECT`'s layout, in the set's own space, culled
against the view and drawn level by level from what the GPU kept, with no count on the CPU. In the world, a set is one
draw: `CgWorldRenderer` builds the pyramid from the stage's depth ahead of its own passes (in Minecraft, the terrain),
culls every set in every stage that draws it, all at once, and draws each level kept.

```java
world.draw(rockLods, stone).instances(rocks, CgGpuCount.of(n)).at(x, y, z).bounds(field).submit();
```

By hand, into a recording:

```java
CgCull cull = new CgCull().mesh(rockLods);                           // once
CgGpuOps.depthPyramid(recording, stage.target(), stage.constants(), depth);   // after what hides them drew
CgComputePass pass = recording.compute("rocks.cull");
CgGpuOps.cull(pass, cull.view(view, projection).place(place).pyramid(depth), rocks, CgGpuCount.of(n), visible, counts, 0);
pass.end();
for (int l = 0; l < cull.levels(); l++) {
    chunks.draw(pipeline, bindings, rockLods.level(l)).objects(visible, CgGpuOps.cullFirst(l, n), n)
          .indirect(counts, l * 4L, CgIndirect.INSTANCES, 1);
}
```

- `visible` holds `CgGpuOps.cullRecords(cull, n)` records; `counts` a word a level. The place and the view are
  camera-relative, the camera subtracted in doubles.
- A kernel of your own tests a box against the pyramid with `cg_occluded(pyramid, size, clipX..clipW, eye, lo, hi)`,
  from `crystalgraphics:shaders/compute/ops/occlusion.glsl`.
- The pyramid is `CgGpuOps.PYRAMID_FORMAT` with mips, the size of the target it reads; one built from this frame's
  depth hides what is behind the host's world as drawn so far.
- Where draws join, the levels are one multi-draw call (`render/graph/CLAUDE.md` § *Multi-draw*).
- Its gate is `--mode=gpu-cull`: the same picture as the world renderer's CPU cull, byte for byte, by hand and as a
  world draw.

**`lib/rng.glsl`** is a counter-based generator (PCG4D): `cg_rng4(seed, element, step, stream)`, any element drawing
its own numbers in any order, integer-exact on every device, with `CgRng` giving the same bits in Java. Key an element
by an id it carries, never its slot, or compaction reshuffles its numbers.

**What each op costs**, GPU ms on an RTX 4070 SUPER at a million elements and a 1920x1080 chain (the harness's
`gpu-ops-cost` prints it for any machine):

| Op | GPU ms |
|---|---|
| fill, iota, copy | 0.02-0.04 |
| reduce | 0.03 |
| scan, compact | 0.08, 0.11 |
| bounds | 0.29 |
| histogram, 64 bins | 0.01 |
| sort, 32 bits | 0.42 |
| downsample, blur | 0.03-0.53 |

### Designing a kernel

**1. Pick the narrowest shape.** It says what the kernel does, and the compiler holds it to that.

| The kernel… | Write it as |
|---|---|
| updates each element from itself | `map` |
| reads other elements: neighbours, a lookup | `gather` |
| emits zero or more new elements: spawning, trails | `append` |
| writes at a computed index: binning, splatting, counting | `scatter` |
| writes each texel of an image | `image` |
| sums, prefix-sums, compacts, sorts, bins, takes bounds | a [`CgGpuOps`](#ops-cggpuops) op, not a kernel of your own |
| uses `shared` memory, `barrier()` or subgroups | `general` |

**2. Reading an element another invocation writes is a race.** State that advances is a history buffer (`IN` reads
the newest version, `OUT` writes the next), and work that needs another's result is a second dispatch, which the
graph orders.

**3. To claim a slot, append; to number survivors, `CgGpuOps.compact`.**

**4. Scale the work by a budget.** An effect sized for the author's GPU runs on a smaller one at a fraction of the
speed. A `CgGpuBudget` times the passes charged to it on the GPU and answers a scale that holds them inside its
milliseconds: multiply what is spawned or simulated by it. Size capacity for the most you will spawn, since a budget
scales work, not storage.

```java
static final CgGpuBudget SPARKS = CgGpuBudget.define("sparks", 1.5f);        // 1.5 ms of GPU a frame
CgComputePass step = recording.compute("sparks.step").timed(SPARKS);       // charged to it: raster passes too
int spawned = Math.round(wanted * SPARKS.scale());                          // in [0.1, 1]
```

- It starts at 1, drops at once when over and rises after a run well under; what it measures lags the work by a few
  frames and never waits for it.
- An `async()` pass on a device with a compute queue runs beside the timer and is not counted.

**5. Integers where the answer must match.** Integers and `cg_rng` give the same bits on every device and in Java;
floats agree to a rounding. A decision, a count or a seed that must match across machines is integer math or
`cg_rng`, keyed by an id the element carries, never its slot.

### Easy to get wrong

- **The shape is checked against everything a kernel reaches**, helpers and macros included: a `map` calling a helper
  that calls `barrier()` is refused with the kernel, the name and the line.
- **A struct comes before `Buffers { }`**: the generated declarations stand where the block stood.
- **A `general` kernel has no early return**: it checks `CG_IN_RANGE` after its barriers.
- **An append buffer's count is the caller's**: bound with `.counter(name, buffer, offset)` and zeroed before the
  dispatch (`recording.fill`).
- **An unbound buffer, count or image** a kernel uses throws at `pass.end()`, naming the pass, the kernel and the
  binding.
- **`vec3`** is refused as a property.
- **A `CgKernelProgram` dispatch orders nothing after it**; a graph's dispatch is fenced by the executor.
- **Limits are the device's, checked at `program()`**: the size per axis, invocations, shared memory.
- **New graph buffer storage is not zeroed.**

### Debugging and testing

```java
String glsl = kernel.glsl();                // the source it compiles from on this context, includes unexpanded
```

- A kernel that fails to compile throws with the driver's log and the emitted source, numbered. One that fails to
  read throws `CgShaderError` from `CgCompute.load`, naming the file, the kernel and the line.
- **Checked mode**, `-Dcrystalgraphics.compute.checked=true`: every buffer and image access a kernel makes is
  bounds-checked, one out of range is skipped, and the first of each dispatch is logged a frame or two later,
  once a place (`CgComputeCheck.reported()` lists them):

  ```
  [crystalgraphics] compute check: mymod:shaders/bins.compute, kernel Bin, line 24: BINS_ADD at 64, past BINS's 64 elements
  [crystalgraphics] compute check: mymod:shaders/heat.compute, kernel Paint, line 29: HEAT_WRITE at (32, 8), outside HEAT's 32x32 (256 times in one dispatch)
  ```

  `NAME_DATA[i]` and appends are not checked. A debugging switch: every access tests its index.
- **What a buffer holds**, field by field: `CgBufferInspector` reads any buffer a compute pass binds, after that pass,
  and decodes it through the kernel's own declaration, without a stall (`render/graph/CLAUDE.md`
  § *Inspecting a buffer*):

  ```java
  CgBufferInspector.watch(true);
  for (CgBufferInspector.Site site : CgBufferInspector.sites()) System.out.println(site);
  // sparks after particles.step: 4096 x Spark, 32 bytes each (mymod:shaders/particles.compute Step, STATE)
  CgBufferInspector.read(site, 0, 16, read -> System.out.println(read.value(0, site.decl().field("positionLife"))));
  ```
- An asset reload (`CgAssetReloader`) re-reads every `.compute`; `CgCompute.load(path).reload()` re-reads one.
- `CgComputeSelfTest` ships: one kernel per shape, every result worked out in Java.
  `-Dcrystalgraphics.compute.selfTest=true` logs its verdict on any client a frame or two after its first, never
  waiting on the GPU (Minecraft's Vulkan device included), and the harness's
  `compute-tiers` runs it. `gpu-ops` checks every op against Java; `shader-compile-audit` compiles every shipped
  kernel on the driver.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers --seconds=5 --rhi=vulkan"
./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-ops --seconds=30"
./gradlew prodSmoke -PcgTargets=<labels> -PcgSmokeProps=crystalgraphics.compute.selfTest=true
```
