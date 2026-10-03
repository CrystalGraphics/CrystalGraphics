# Shaders and kernels — `.shader`, `cg_env.glsl`, engine buffers, `.compute`

Moved from [`AGENTS.md`](../AGENTS.md), which keeps the rules every session needs. Loads itself, imported by the `CLAUDE.md` of the packages it describes: `api/material`, `api/shader`, `gl/material`, `gl/shader`, `mc/shader` and `compute`. Nothing under `resources/` loads it, since all of it ships: read it before touching a `.shader`, `.compute` or `.glsl` file.

## CrystalShader Material Pipeline

The primary authoring surface of this repo. Most feature work touches these systems.

### The `.shader` File Format

**Reference file**: `src/main/resources/assets/crystalgraphics/shaders/example.shader`
→ Read this file first. Every feature is documented inline with comments.

Structural skeleton (all sections are optional except `#type` and at least one `Pass`):

**`#type <name>`** selects the vertex format by its registered `key`. The compiler resolves the name against `CgVertexFormat.REGISTRY` at parse time and injects the format's vertex attribute declarations (`in <glslType> <name>;`) into the generated vertex GLSL immediately after the `cg_env.glsl` include. Unknown names throw `CgShaderParseException` at parse time listing all registered types.

Built-in types: `spatial` (`CgVertexFormat.SPATIAL` — pos3/uv2/normal3), `pos3_uv2_col4ub`, `pos2_uv2_col4ub`, and `none` (`CgVertexFormat.NONE`, no attributes: every quad, curve and text shader, drawn on `CgMesh.quads(1)`). Custom formats self-register on `CgVertexFormat.build()` under their `debugName` and become immediately usable as a `#type`.

```glsl
#type spatial
#pragma cg_feature RECEIVE_SHADOWS    // compile-time keyword; max 8 per shader
#pragma cg_feature FOG_ON
#pragma cg_use quad                   // opt into an engine buffer (see below); omit if unused

Tags { "RenderType" = "Opaque" }      // controls shadow auto-generation; "Lighting" = "Unlit", "Fog" = "Off" opt out
Queue = "Geometry"                    // Background|Geometry|AlphaTest|Transparent|Overlay

Properties {
    _MainTex   ("Main Texture", sampler2D) = "white"
    _Color     ("Tint Color",   color)     = (1, 1, 1, 1)
    _Roughness ("Roughness",    float)     = 0.5
}

struct v2f { vec2 uv; vec3 worldPos; };

Pass {
    Tags { "LightMode" = "Forward" }  // Forward (default) | ShadowCaster | Depth
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }
    void vertex(out v2f o) { ... }
    void fragment(in v2f i, out vec4 fragColor) { ... }
}
// ShadowCaster pass is auto-generated for Opaque + castShadows=true + queue < 3000
```

### cg_env.glsl — The Backbone

`src/main/resources/assets/crystalgraphics/shaders/env/cg_env.glsl` is the foundation every `.shader` file stands on: the frame block, the per-instance object data with its SSBO/TBO dual path, the instance-id bridge, the scene samplers and the convenience macros over them. **A renderer's own macros are not in it** — `CG_QUAD_*` lives in `env/buffer/quad.glsl` and `CG_CURVE_*` in `env/buffer/curve.glsl`, injected only by `#pragma cg_use`; three quarters of this file used to be those two, paid for by every shader including the ones that draw neither. The material compiler **automatically `#include`s `cg_env.glsl`** into every generated vertex and fragment stage — you never include it manually in `.shader` files. Raw `CgShader` users must `#include "crystalgraphics:shaders/env/cg_env.glsl"` explicitly if they need its symbols.

It provides two distinct layers: per-frame global data and per-instance object data.

#### Frame Data — `CgFrameBlock`

A `layout(std140) uniform CgFrameBlock` wired post-link by the engine. Available in every stage, every pass:

| GLSL name | Type | Content |
|---|---|---|
| `cg_ViewMatrix` | `mat4` | Camera view matrix |
| `cg_ProjMatrix` | `mat4` | Projection matrix |
| `cg_Time` | `vec4` | `(t/20, t, t×2, t×3)` — seconds |
| `cg_Resolution` | `vec2` | Viewport size in pixels |
| `cg_DepthParams` | `vec4` | `x` 1 when the pass's depth is reversed (Minecraft 26.2's world), `y` 1 when its clip depth runs 0..1. Read through `cg_LinearEyeDepth`, not directly |
| `cg_WorldOrigin` | `vec4` | Where world space's origin is in absolute coordinates: the camera, in a camera-relative world pass. Read through `CG_ABSOLUTE_WORLD_POS(p)` |
| `cg_SunDirection` | `vec4` | `xyz` the direction toward the sun (the moon while it is down), `w` daylight 0..1. Read through `CG_SUN_DIRECTION`, `CG_DAYLIGHT` |
| `cg_FogColor` | `vec4` | The world's fog colour, `a` 1 when there is fog |
| `cg_FogParams` | `vec4` | `x` fog start, `y` fog end, in blocks from the eye. Read through `cg_FogAmount` |

**Scene samplers** — auto-bound by the engine before every material draw; do not declare or bind these yourself:

| GLSL name | Type | Unit | Content |
|---|---|---|---|
| `cg_DepthBuffer` | `uniform sampler2D` | `CgBindingPoints.DEPTH_TEXTURE_UNIT` | The target's depth as it stands at the draw, in its own depth format: a copy the frame graph takes before the first reader and again after a draw that wrote depth (`render/graph/CLAUDE.md` § *Reading the target*). An opaque material sees the host's world and the opaque draws sorted before it, a transparent one every opaque draw. **Raw values are the host's convention** — reversed-Z on 26.2 — so compare depths as eye distances: `CG_SCENE_EYE_DEPTH(uv)` against `cg_LinearEyeDepth(gl_FragCoord.z)`. Valid in both vertex and fragment stages of all passes. **Do not bind user Properties samplers to `CgBindingPoints.DEPTH_TEXTURE_UNIT`.** |
| `cg_SceneColor` | `uniform sampler2D` | `CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT` | The target's colour as it stands at the draw, copied the same way: everything sorted before the reader, transparent draws included, but other readers in a row with it, which share its copy. RGBA8, linearly filtered, for a material that bends what is behind it (heat haze, a shockwave). **Do not bind user Properties samplers to that unit either.** |
| `cg_Lightmap` | `uniform sampler2D` | `CgBindingPoints.LIGHTMAP_TEXTURE_UNIT` | The host's lightmap in a world pass, block light along u and sky light along v; 1x1 white in any other pass. Read through `CG_LIGHTMAP(light)`. **Do not bind user Properties samplers to that unit.** |

Convenience macros over the frame block:

| Macro | Expands to | Use |
|---|---|---|
| `CG_TIME` | `cg_Time.y` | Raw seconds — the one you want 99% of the time |
| `CG_TIME_VEC4` | `cg_Time` | Full 4-component time vector |
| `CG_RESOLUTION` | `cg_Resolution` | Viewport dimensions in pixels |
| `CG_SCENE_EYE_DEPTH(uv)` | `cg_LinearEyeDepth(texture(cg_DepthBuffer, uv).r)` | Scene distance from the camera at `uv`, in eye units, under any depth convention |
| `CG_SCENE_COLOR(uv)` | `texture(cg_SceneColor, uv)` | The scene's colour at `uv` (`gl_FragCoord.xy / CG_RESOLUTION` for the pixel behind) |
| `CG_MATRIX_MVP` | `cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD` | Standard MVP transform |

#### Lighting and fog — on by default

A world material is lit by the host's lightmap and fogged as Minecraft's own blocks are, with no code of its own. The compiler's generated fragment `main` sets `cg_Light` (block and sky light, 0 to 15) from the draw, calls `fragment()`, then applies `cg_Lit` and `cg_Fog` to its colour. This covers materials reading the object record, meaning those using neither `#pragma cg_use quad` nor `curve`, in a Forward pass with one output.

```glsl
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }   // an emissive effect: fogged, never darkened
Tags { "Lighting" = "Unlit" "Fog" = "Off" }                   // a sky, or a haze that bends what is already fogged

void fragment(in v2f i, out vec4 fragColor) {
    cg_Light = i.light;                       // a draw of many lights (particles) sets its own, before anything reads it
    fragColor = vec4(albedo * max(dot(n, CG_SUN_DIRECTION), 0.2), 1.0);   // lit by the lightmap after
}
```

| Name | Is |
|---|---|
| `cg_Light` | fragment only: this fragment's `vec2(block, sky)`, `CG_OBJECT_LIGHT` unless the shader sets it |
| `CG_OBJECT_LIGHT` | the draw's light, in the normal matrix's unused column: `CgWorldRenderer`'s `.light`, else the world's at its position |
| `CG_LIGHTMAP(light)` | the lightmap's colour for a `vec2(block, sky)` |
| `cg_Lit(color)`, `cg_Fog(color)` | fragment only: what the generated `main` applies, for a material that opted out and wants them on part of its colour |
| `cg_FogAmount(distance)`, `CG_FOG_AMOUNT(worldPos)` | 0 to 1, vanilla's smoothstep from `cg_FogParams` |
| `CG_FOG_MODE` | set by the compiler from the pass's blend: 0 mixes toward the fog colour, 1 does so premultiplied, 2 (additive) fades the colour out |

- The tags take `Lit`/`Unlit` and `On`/`Off`; anything else fails to parse.
- A shader writing `cg_Light` reads `CG_LIGHTMAP` with it too: a smoke billow mixes its fire glow out of the lightmap's reach.

#### Per-Instance Object Data — SSBO / TBO Dual Path

Every draw uses instancing. Per-object data lives in an engine-managed buffer. At startup, `CgCapabilities.detect().shaderBufferPath()` picks one of two hardware paths:

| Path | Condition | Backing |
|---|---|---|
| **SSBO** (`CG_USE_SSBO` defined) | Core GL 4.3+ or ARB | `layout(std430) readonly buffer CgObjectDataBuffer { CgObjectData cg_Objects[]; }` |
| **TBO** (fallback) | GL 3.3 baseline | `uniform samplerBuffer CgObjectDataBuffer` + `cg_FetchObjectData(int)` helper |

The struct is the same on both paths:

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

Shaders never branch on the path — the macro surface is identical regardless:

| Macro | Expands to | Notes |
|---|---|---|
| `CG_OBJECT_TO_WORLD` | `CG_OBJECT_DATA.modelMatrix` | Model → world transform |
| `CG_NORMAL_MATRIX` | `mat3(CG_OBJECT_DATA.normalMatrix)` | Upper-left 3×3 — use for transforming normals |
| `CG_OBJECT_CUSTOM0`–`CG_OBJECT_CUSTOM3` | `CG_OBJECT_DATA.custom0` … `.custom3` | Per-instance `vec4` slots — a world draw's `custom(slot, …)` |
| `CG_INSTANCE_ID` | `gl_InstanceID + cg_InstanceBase` (vertex) / `cg_InstanceId` (fragment) | Instance index; bridged as `flat in int cg_InstanceId` varying so it's accessible in fragment. `cg_InstanceBase` is where a batch's instances start in its kind's upload — 0 unless a frame-graph executor sets it (`CgPipeline.instanceBase`). Below 0 it names the one record every instance reads (`CgPipeline.sharedInstance`): an indirect `INSTANCES` draw's |
| `CG_DRAW_INSTANCE` | `gl_InstanceID` (vertex only) | The instance's index in its own draw: in an indirect `INSTANCES` draw, the element it draws |
| `CG_VERTEX_ID` | `gl_VertexID - cg_VertexBase` (vertex only) | The vertex's index in its own mesh, wherever the mesh sits in the buffer it is drawn from. `cg_VertexBase` is the mesh's base vertex — 0 unless the draw sets it (`CgPipeline.vertexBase`) |
| `CG_VERTEX_CORNER` | `vec2` from `CG_VERTEX_ID` (vertex only) | The corner of a `CgMesh.quads(n)` vertex: (0,0), (1,0), (1,1), (0,1) around each quad. What `CG_QUAD_*` and `CG_CURVE_*` place an instance's corners by |

#### Vertex Attribute Aliases

Available in the vertex stage only. Locations are bound by `CgShaderFactory` before link — no `layout(location=N)` needed in shader code:

| Alias | Type | Location |
|---|---|---|
| `cg_Position` | `vec3` | 0 |
| `cg_TexCoord0` | `vec2` | 1 |
| `cg_Normal` | `vec3` | 2 |

#### Why the Engine's Blocks Must NOT Be Attached

`cg_env.glsl` already declares `CgObjectDataBuffer` and `CgFrameBlock`. The engine wires them automatically post-link. **Never `material.attach()` a buffer named for either** — it produces duplicate GLSL declarations and a compile failure. Only user-owned buffers belong in `attach()`.

#### Stage defines — `CG_VERTEX_STAGE` / `CG_FRAGMENT_STAGE` / `CG_COMPUTE_STAGE`

One `.shader` file becomes **two** GLSL programs, and `partitionGlobalDecls` hoists **every** `#`-line
from a material or pass preamble — `#include` very much included — into **both** of them. There is no
stage filtering, by design: a shader author writes one preamble, not two. A kernel is a third stage, and
includes `cg_env.glsl` and its engine buffers' env files like any material.

The consequence is the rule:

> **A lib included at material scope is compiled into the vertex stage too, and an env file into every
> stage. If any of it is fragment-only, it must guard itself.**

The compiler emits exactly one of these into each generated source, **before** the user directive
block, so an included lib can see it:

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

**Name the stages that cannot have it, never `#ifdef CG_FRAGMENT_STAGE`.** Raw `.vert`/`.frag` files go
through `CgShaderPreprocessor` with *no* stage defines at all, so an `#ifdef` silently deletes the
function from every one of them. A kernel drops every function it does not call, so a lib's guard only
matters to one that calls it; an env file's guard is what lets a kernel `#pragma cg_use` its buffer.

> **Both facts here were learned the expensive way.** `sdf.glsl`'s `fwidth` reached the vertex stage
> and an AMD tester could not launch the UI gallery at all, while it ran flawlessly on NVIDIA —
> NVIDIA accepts derivative builtins in a vertex shader, AMD correctly refuses. And the define used
> to be emitted *after* the user `#include`s, which meant every guard evaluated identically in both
> stages and did nothing at all. Ordering is what makes the guard mechanism exist.

Fragment-only, i.e. never legal in a vertex shader or a kernel: `fwidth`/`dFdx`/`dFdy` and their
`Fine`/`Coarse` variants, `discard`, `gl_FragCoord`, `gl_FrontFacing`, `gl_PointCoord`, `gl_FragDepth`,
`interpolateAt*`, `gl_SampleID`/`gl_SamplePosition`/`gl_SampleMask`.

Three things enforce this so it cannot regress silently:

- **`ShippedShaderStagePurityTest`** (in both CrystalGraphics and CrystalGUI) compiles every shipped
  `.shader` and asserts no such identifier is *reachable* in the generated vertex source;
  **`ShippedKernelStagePurityTest`** does the same for every shipped kernel, against vertex-only names
  too. Both resolve the stage conditionals through `CgShaderStages`, because `CgShaderPreprocessor`
  expands `#include` but leaves `#if` to the driver. GL-free, so they catch this on any machine.
- **`--mode=shader-compile-audit`** puts the real driver over every shipped shader, kernel and keyword
  variant, collecting failures instead of crashing on the first. Run it on any GPU that disagrees.

---

### Keyword Variants

Each `#pragma cg_feature` declares a compile-time flag. Enabling a keyword at runtime injects `#define NAME 1` into both vertex and fragment. **Every unique combination of enabled keywords is a separately compiled and cached GL program** — zero runtime branch cost.

```glsl
#pragma cg_feature RECEIVE_SHADOWS
#pragma cg_feature FOG_ON
#pragma cg_feature NORMAL_MAP   // max 8 per shader
```

| Rule | Detail |
|---|---|
| Max 8 per shader | Exceeding throws `CgShaderParseException` at parse time |
| Uppercase only | Names must match `[A-Z][A-Z0-9_]*` |
| Declared names only | `enableKeyword()` throws `IllegalArgumentException` for undeclared names |
| Lazy compilation | Variant compiled on first `bind()` with that keyword set, then cached under a flat `ProgramKey` |
| OFF by default | No `#define` is injected unless explicitly enabled |
| Lines stripped | `#pragma cg_feature` lines never appear in generated GLSL output |

```java
material.enableKeyword("RECEIVE_SHADOWS");  // compiled + cached on next bind()
material.disableKeyword("FOG_ON");
boolean on = material.isKeywordEnabled("NORMAL_MAP"); // false by default
```

---

### Engine Buffers — `#pragma cg_use`

`CgFrameBlock` and `CgObjectDataBuffer` are declared unconditionally in `cg_env.glsl` because
essentially every shader wants them. Buffers that only a minority of shaders need are **opt-in**:

```glsl
#type none
#pragma cg_use quad
```

| Token | Provides | Needed by |
|---|---|---|
| `quad` | `QUAD_DATA(n)` + `CG_QUAD_WORLD_POS` / `CG_QUAD_UV` / `CG_QUAD_COLOR` / `CG_QUAD_NORMAL` / `CG_QUAD_ATLAS_LAYER` / `CG_QUAD_CUSTOM0`-`CG_QUAD_CUSTOM1` (two free vec4s per instance, written with `Quad.custom0`/`custom1` — `CG_OBJECT_CUSTOM*`'s contract at quad granularity, and where a per-quad parameter belongs rather than in a property that breaks the batch), and for screen-space materials the edge and texel antialiasing below | Any shader drawn through `CgQuadRenderer` — UI quads, text glyphs, SDF rects |
| `curve` | `CURVE_DATA(n)` + `CG_CURVE_WORLD_POS` / `CG_CURVE_P0`–`P2` / `CG_CURVE_COLOR0`–`1` / `CG_CURVE_WIDTHS` / `CG_CURVE_FEATHER` / `CG_CURVE_FLAGS` | Any shader drawn through `CgVectorRenderer` — Bézier strokes, graph wires, connectors |
| `palette` | `PALETTE_DATA(n)` + `cg_spatial_point`/`_vector`/`_covector`/`_scale`, `cg_effect_opacity`, and fragment-only `cg_spatial_from_fragment` — the raster pass's property trees (`CgPalette`): each spatial node's affine into the target, each effect node's opacity. `quad`, `curve` and `clip` bring it; `CG_QUAD_*` and `CG_CURVE_*` positions are already mapped through it | Never declared by hand. A material that honours group opacity multiplies by `CG_QUAD_OPACITY`/`CG_CURVE_OPACITY` |
| `clip` | `CLIP_DATA(n)` + `CG_CLIP_QUAD_COVERAGE` / `CG_CLIP_CURVE_COVERAGE` — the coverage of the `CgClipTable` entry the instance names (`Quad.clip`, `Curve.clip`, `CgTextRenderer.clip`), 1 for entry 0. Fragment stage only; read it before any `discard` | Any quad or curve material a rounded clip must reach: every CrystalGUI UI material, and `text.shader`. A material that does not multiply by it draws past the corners |
| `particle` | `PARTICLE_DATA(n)` + `CG_PARTICLE_POSITION` / `CG_PARTICLE_SIZE` / `CG_PARTICLE_VELOCITY` / `CG_PARTICLE_PROGRESS` / `CG_PARTICLE_SEED` / `CG_PARTICLE_SPIN` / `CG_PARTICLE_HEAT` / `CG_PARTICLE_OPACITY` / `CG_PARTICLE_LIGHT` — the frame's particle records (`CgParticleBuffer`, FRAME lifetime, four vec4s each, the fourth its `vec2(block, sky)` light), written once a frame by whatever simulates particles; a draw reads its own range `[base, base + count)`. Write it every frame any particle draws | Particle renderers: the vfx engine's quads and arcs (plan `vfx-particles`) |

> **A screen-space quad material antialiases its own edges — without MSAA.** `env/buffer/quad.glsl`
> (injected by `#pragma cg_use quad`) provides
> `CG_QUAD_EDGE_PARAM` (the vertex's parameter, grown by half a pixel when the instance is rotated or
> sheared in device space), `CG_QUAD_EDGE_WORLD_POS(param)`, `CG_QUAD_EDGE_UV(param)` (clamped, so the pad
> never samples past the rect) and `CG_QUAD_EDGE_COVERAGE(param)` — the exact area a straight edge leaves of
> the pixel, per edge, combined per opposite pair. Axis-aligned instances are left exactly alone: the
> rasteriser snaps those, and two abutting quads softened on a fractional boundary would seam. **Every edge
> of a rotated quad is soft**: there was a per-edge opt-out for a quad abutting another, and nothing tiles
> with separate quads any more — a nine-slice is one draw remapping its regions per pixel, so the seams are
> inside a fragment shader rather than between quads. `CG_QUAD_EDGE_ROTATED` gates anything else a material wants to do only when
> rotated: `cg_texel_aa_sample` (the pixel-art filter: nearest everywhere, one screen pixel of blend at a
> texel boundary, four taps held inside `CG_QUAD_UV_RECT`) — which is not a quad thing at all and lives in `lib/texel.glsl`, `#include`d where wanted, since it reads no instance buffer and `sdf_coverage(dist, rampPx)` with
> `CG_QUAD_EDGE_FILTER` — the reconstruction width, 1.5 px, the one knob. Adoption is three lines per
> material; every CrystalGUI quad material has it, `text.shader`'s bitmap path has the texel filter, and a
> 3D quad material must not use any of it (half a pixel means nothing under a perspective projection).

> **A record names a spatial and an effect node** (`Quad.node`, `Curve.node`, `CgTextRenderer.node`), recorded in a
> `CgRecording`'s `spatial()` and `effects()` trees; node 0 is the target's own space, at opacity 1. The executor writes
> each raster pass's palette from the recorded values and a `CgPropertyValues` (`CgFrameGraph.add(recording, values)`),
> so a compositor moves or fades what was recorded by writing values and executing the built frame again. A pass into
> a layer names where it sits (`CgRasterPass.view`), and scissors and clip entries may name a node and move with it.

> **A rounded clip is an instance field, not a render target.** `CgClipTable.add` records a rounded box in its
> own space (rect, radii, a border's inner edge), the inverse of the pose that put it in the bound target, and
> the entry it sits inside; it answers an index. An instance stamped with it multiplies its output by the
> coverage of every entry up the chain (`MAX_DEPTH`, 4), each antialiased as a `gui_box` shape's own edge, so any
> rotation or skew clips exactly. Changing the clip never flushes; the renderers upload and bind the table
> when they draw. An entry means nothing in another target and nothing next frame. `addPixelRect` is the square
> case at whole pixels: the pixel-centre test a scissor makes, with no batch break, and a rect nested in another of
> the same node merges into one entry.

> **`curve` is the one engine buffer read from the fragment stage as well as the vertex stage.** A
> stroke is an analytic SDF evaluated per pixel, so the fragment needs the control points themselves;
> it re-reads `CURVE_DATA(CG_INSTANCE_ID)` rather than receiving them as varyings, because the
> `.shader` v2f DSL has no `flat` qualifier to offer (`cg_InstanceId` is the only compiler-generated
> flat varying). This needs no compiler change — `appendAttachedBuffers` already runs for the fragment
> source too — but it does mean the TBO fallback occupies that texture unit in both stages.

The buffer's GLSL declaration is injected **during parsing, before anything can compile**, so the
symbols exist no matter what triggers the first compile. **A token may also carry an env file** —
GLSL macros written against the buffer, the way `CG_QUAD_*` is written against `QUAD_DATA` — which
`CgMaterialShaderCompiler` includes immediately after the declaration, so the macros always have the
struct to read. Convention is `shaders/env/buffer/<token>.glsl`; a mod's own buffer gets the same
treatment by passing its own path. Register a new token with
`CgEngineBufferRegistry.register(...)` — providers hold a `Supplier`, so registration never forces
the provider class's static init (which would allocate against `CgBindingPoints` too early).

**Both directions are hard parse errors:**

| Mistake | Result |
|---|---|
| Uses `CG_QUAD_*`/`QUAD_DATA` without `#pragma cg_use quad` | `CgShaderParseException` naming the line to add |
| Declares an unregistered token | `CgShaderParseException` listing registered tokens |
| Duplicate or malformed token | `CgShaderParseException` |

> **Do not attach these from Java.** A `CgQuadRenderer.attachTo(material)` helper used to exist and
> was removed. Attaching at first use loses to anything that compiles the shader earlier — notably
> `CgMaterial.enableKeyword`, which recompiles on the spot when the shader has not been parsed yet.
> The result was GLSL built with `QUAD_DATA` undeclared, and because a failed compile leaves the
> parsed feature list empty, it surfaced as **"Keyword 'X' is not declared as `#pragma cg_feature`"**
> — naming a pragma that was present and correct. Declaring the dependency in the shader removes the
> ordering question entirely.

Like `cg_feature`, `cg_use` lines are stripped and never reach generated GLSL, and are only read
from the material-level preamble (never from inside a `Pass` body).

---

### Passes, LightMode Routing, and MRTs

#### LightMode Routing

Each `Pass { }` carries a `Tags { "LightMode" = "..." }` that routes it to the correct rendering stage:

| LightMode | Stage | Notes |
|---|---|---|
| `Forward` | Standard forward-lit draw | Default when `LightMode` is absent |
| `ShadowCaster` | Depth-from-light pass | Auto-generated for `RenderType=Opaque`, `castShadows=true`, `queue < 3000` |
| `Depth` | Early depth pre-pass | Auto-generated for opaque materials |
| `Emissive` | The world's bloom: light the material gives off, blurred over the scene | Never generated; at most one per shader. Below |

The `"Name"` tag sets the pass key dimension for the `ProgramKey` variant cache. Auto-assigned as `Pass0`, `Pass1`, … when absent.

#### The Emissive pass

What a material draws in its Emissive pass is the light it gives off: `CgWorldRenderer` draws it into its bloom
target after the transparent pass, blurs it and adds it over the world (`docs/ENGINE_API.md` § *CgWorldRenderer*).
Unity's, Godot's and Unreal's emission is a material output added to an HDR scene colour; Minecraft's target is
8-bit, so here it is a pass that draws the mesh again into a float target.

```glsl
// The Forward pass's code and render state again: what it draws, it also blooms
Pass { Tags { "LightMode" = "Emissive" } }

// Or its own: only the bright part blooms
Pass {
    Tags { "LightMode" = "Emissive" }
    void vertex(out v2f o) { gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0); o.uv = cg_TexCoord0; }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_GlowColor.rgb * _GlowStrength, 0.0); }
}
```

- **Write HDR colour with an alpha of 0.** It adds into a float target; the alpha is unused.
- **Codeless**, it takes the first Forward pass's v2f, code and render state, and fails to parse when no Forward pass
  comes before it. A `RenderState` of its own replaces the Forward pass's.
- **With code and no `RenderState`**, it draws ONE ONE with no depth test and back faces culled. An authored state
  replaces all of it, including the cull, so list everything. ONE ONE is not `CgBlendState.ADDITIVE`, which is
  SRC_ALPHA ONE and adds nothing at alpha 0.
- **Hidden by the scene, not by a depth test.** The bloom target has no depth, so the compiler discards a fragment
  further than the scene's depth at its pixel, with slack for the depth buffer's precision (`CG_EMISSIVE_DEPTH_SLACK`,
  `CG_EMISSIVE_DEPTH_BIAS`, in `cg_env.glsl`). `DepthTest ALWAYS` turns that off for a shader that tests depth itself:
  a volume drawn on its back faces.
- Unlit and fogged additively whatever the material's tags. `CG_EMISSIVE_PASS` is defined in both stages, so a body
  it shares with the Forward pass can tell them apart.
- It takes the material's keywords, as the Forward pass does.

#### Pass Types vs. Multi-Draw Chains — Two Orthogonal Axes

The `.shader` format handles **pass type routing** via `LightMode` tags — one `Forward` pass, optionally a `ShadowCaster`, optionally a `Depth`. These are different rendering stages, not sequential draws of the same mesh.

`CgMaterial.nextPass` handles the **orthogonal axis**: the same mesh drawn twice (or more) for decorative effects — outline, additive glow, stencil fill. Each entry in the chain is a fully independent `CgMaterial` with its own shader, render state, and properties. This is NOT Unity built-in multi-pass (multiple `Pass` blocks in one shader file). It is analogous to Godot's `next_pass` but with deterministic immediate ordering instead of Godot's sort-queue execution, which has known ordering bugs.

`drawChain` traverses the chain for a hand-bound draw; a recorded one draws each link as its own draw on the same instances — `CgWorldRenderer` does it for every submitted draw.

```java
// Outline effect: chain a second material that draws enlarged with inverted normals
CgMaterial base    = CgMaterial.load("mymod:shaders/base.shader");
CgMaterial outline = CgMaterial.load("mymod:shaders/outline.shader");
base.setNextPass(outline);

// A recorded draw takes the chain: base, then outline, on the same instances
CgWorldRenderer.get().draw(mesh, base).at(x, y, z).submit();

// A hand-bound draw of geometry the caller bound:
base.drawChain(CgRenderPassVariant.FORWARD, () -> CgGL.glDrawArraysInstanced(CgGL.GL_TRIANGLES, 0, vertexCount, n);   // geometry the caller bound)
```

For explicit single-pass control (no chain):

```java
material.bind();                                     // activates Forward pass, current keywords
CgGL.glDrawArraysInstanced(CgGL.GL_TRIANGLES, 0, vertexCount, n);   // geometry the caller bound
material.unbind();

// Shadow pass (no keywords applied — shadow passes always use empty keyword set):
if (material.hasShadowCasterPass()) {
    shadowMapFbo.bind();
    material.bindForPass(CgRenderPassVariant.SHADOW);
    CgGL.glDrawArraysInstanced(CgGL.GL_TRIANGLES, 0, vertexCount, n);   // geometry the caller bound
    material.unbind();
    shadowMapFbo.unbind();
}
```

#### MRT (Multiple Render Targets)

For G-buffer or deferred passes, declare a named output struct instead of `out vec4 fragColor`. Annotate each field with `: RT0`, `: RT1`, etc. to map to color attachment slots. The compiler expands each to `layout(location=N) out vec4`:

```glsl
// Declare above the Pass block or at material scope
struct GBuffer {
    vec4 albedo  : RT0;   // → layout(location=0) out vec4 → GL_COLOR_ATTACHMENT0
    vec4 normal  : RT1;   // → layout(location=1) out vec4 → GL_COLOR_ATTACHMENT1
    vec4 pbr     : RT2;   // → layout(location=2) out vec4 → GL_COLOR_ATTACHMENT2
};

Pass {
    Tags { "LightMode" = "Forward" "Name" = "GBufferFill" }
    void vertex(out v2f o) { /* ... */ }
    void fragment(in v2f i, out GBuffer o) {
        o.albedo = texture(_MainTex, i.uv) * _Color;
        o.normal = vec4(normalize(i.normalWs) * 0.5 + 0.5, 1.0);
        o.pbr    = vec4(_Roughness, _Metallic, 0.0, 1.0);
    }
}
```

---

### Loading and Using a Material

```java
CgMaterial mat = CgMaterial.load("mymod:shaders/terrain.shader"); // cached per path
// CgMaterial.newInstance("...") creates a fresh non-cached instance

// Properties (persist across frames until overwritten):
mat.applyProperties(b -> {
    b.set1f("_Roughness", 0.8f);
    b.vec4("_Color", 1f, 0f, 0f, 1f);
});
// Safe to call before first bind() — buffered and replayed after compile

// Keywords:
mat.enableKeyword("RECEIVE_SHADOWS");  // compiles+caches variant lazily on next bind()
mat.disableKeyword("FOG_ON");
boolean on = mat.isKeywordEnabled("FOG_ON");  // false by default
// Throws IllegalArgumentException for undeclared names
```

### GLSL Standard Library

Located at `src/main/resources/assets/crystalgraphics/shaders/lib/`. All files use `#pragma once`.

| File | Key functions |
|---|---|
| `math.glsl` | `saturate`, `remap`, `remap01`, `sq`/`cb`, `positive_pow`, `safe_pow`, `sign_pow`, `smootherstep`, `deg_to_rad`/`rad_to_deg` |
| `vector.glsl` | `safe_normalize`, `fresnel` (Schlick), `rotate_axis` (Rodrigues), `orthonormalize`, `project_onto`/`reject_from`, `angle_between` |
| `color.glsl` | `luminance` (BT.709), `srgb_to_linear`/`linear_to_srgb`, `fast_*` variants (Chilliant), `rgb_to_hsv`/`hsv_to_rgb` (branchless), `rotate_hue`, `desaturate` |
| `uv.glsl` | `rotate_uv`, `scale_uv`, `tile_uv`, `pan_uv`, `flip_uv_x/y`, `cartesian_to_polar_uv` |
| `noise.glsl` | `hash12`/`hash22`/`hash13` (sin-free), `value_noise`, `fbm4`/`fbm6`, `fbm(p, octaves)`, `fbm_ridged` |
| `sdf.glsl` | `sdf_rounded_box` (uniform / per-corner / elliptical), `sdf_segment`, `sdf_bezier` (exact quadratic, with a straight-line fallback), `sdf_coverage` (**fragment-only, guarded**) |
| `texel.glsl` | `cg_texel_aa_sample(tex, uv, uvRect, filterPx)` — pixel-art filtering: nearest everywhere, one screen pixel of blend at a texel boundary. **Fragment-only, guarded.** Takes the reconstruction width as an argument rather than reading a constant, which is what keeps it free of any engine buffer |
| `stroke.glsl` | `stroke_coverage(p, p0,p1,p2, widths, feather, cap, out t)` — the whole shared body of every `CgVectorRenderer` consumer: taper, caps, feathered edge |
| `rect_blur.glsl` | `rect_shadow_outer`, `rect_shadow_inset`, `rect_blur_coverage` — Skia Graphite's analytic Gaussian rect blur with `erf` in place of its integral table; what a text decoration's shadow is. **Fragment-only, guarded** |
| `rng.glsl` | `cg_rng4(seed, element, step, stream)` (PCG4D, counter-based: any element draws its own numbers in any order), `cg_rng`, `cg_rng_unit` (the top 24 bits in [0, 1)), `cg_rng_unit4`, `cg_rng_direction`. Integer-exact on every tier; `compute.ops.CgRng` gives the same bits in Java |
| `text_gamma.glsl` | `text_gamma_terms`, `text_gamma_coverage` — Skia's text gamma and contrast (`SkMaskGamma`), per fragment rather than a table; what `CgTextGamma` drives in `text.shader` |

> **`stroke.glsl` exists so there is exactly one copy of the cap logic.** `curve.shader` and
> CrystalGUI's `gui_curve.shader` must differ in render state (`LEQUAL` vs `ALWAYS`) and in one
> `_LayerOpacity` multiply, and a Pass's `RenderState` cannot vary per keyword variant — so they are
> genuinely two materials. They are not two implementations. The cap handling was wrong three times in
> a row and every version rendered something plausible rather than failing, which is precisely the
> situation where a duplicated body gets fixed in one file only.

Use with `#include "crystalgraphics:shaders/lib/color.glsl"` etc. (`#pragma once` prevents double-expansion when multiple files include `math.glsl`.)

**Package guides for this layer**: `api/material/CLAUDE.md` · `render/CLAUDE.md` · `render/world/CLAUDE.md` · `render/stage` (its classes' javadoc) · `gl/material/CLAUDE.md` · `gl/material/parse/CLAUDE.md`

### Raw Shaders

**Use `CgMaterial.load()` for all shader authoring work.** Raw `CgShader` is for infrastructure-level GL work only — fullscreen blits, post-processing passes, debug utilities, or standalone procedural draws that do not fit the `.shader` material model.

```java
CgShader shader = CgShaderFactory.load(
        "mymod:shaders/blit.vert",
        "mymod:shaders/blit.frag");
```

#### cg_env.glsl is NOT auto-included

Unlike `.shader` materials, raw shader files do **not** get `cg_env.glsl` automatically. If you need the frame UBO, per-instance macros, or vertex attribute aliases, include it explicitly at the top of your `.vert` / `.frag`:

```glsl
#version 330 core
#include "crystalgraphics:shaders/env/cg_env.glsl"
// Now CG_MATRIX_MVP, cg_Time, CG_OBJECT_TO_WORLD, etc. are available
```

#### Binding uniforms

`CgShader` has two binding containers with different lifetimes:

| Method | Lifetime | Typical use |
|---|---|---|
| `shader.applyBindings(b -> { … })` | **Ephemeral** — flushed once on next `bind()`, then auto-cleared | Per-frame values (time, matrices, texture slots) |
| `shader.bindings().set1f(…)` etc. | **Persistent** — survives across frames until explicitly overwritten | Static material parameters |

```java
// Ephemeral (cleared after each bind):
shader.applyBindings(b -> {
    b.set1f("u_time", elapsedSeconds);
    b.sampler2D("u_tex", 0, myTexture);
    b.mat4("u_mvp", mvpMatrix);
});

// Persistent (stays set):
shader.bindings().set1f("u_exposure", 1.0f);
```

> `applyBindings` (raw `CgShader` uniform setters) is a different API from `applyProperties` (`CgMaterial` property block values). Do not confuse them.

#### Scoped bind

`bindScoped()` is a try-with-resources helper that restores the previously bound GL program on exit:

```java
try (CgShaderScope scope = shader.bindScoped()) {
    shader.applyBindings(b -> {
        b.set1f("u_time", elapsed);
        b.sampler2D("u_tex", 0, myTexture);
    });
    CgGL.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);   // a fullscreen triangle
}  // prior program restored automatically
```

#### GLSL library files

Raw shaders can use the same standard library as `.shader` materials. Include any lib file explicitly:

```glsl
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/noise.glsl"
```

`#pragma once` in each lib file prevents double-expansion regardless of include order.

**Package guides**: `api/shader/CLAUDE.md` · `gl/shader/CLAUDE.md` · `mc/shader/CLAUDE.md`

## Compute — the `.compute` file

Kernels in the `.shader` family: the same preprocessor, `#include`, `#pragma cg_feature`, `#pragma cg_use` and
`Properties`, GLSL inside. **`crystalgraphics:shaders/example.compute` is the reference**, as `example.shader` is
for materials; `.compute` files live under `shaders/`, beside what draws their output. Plan:
`plan/crystalgraphics/gpu-compute.md`.

```glsl
#pragma kernel Simulate map            // name, local size (none: 64), shape
#pragma kernel Bin 256 general
#pragma fallback Bin BinScatter         // what a tier without compute runs instead

struct Particle { vec4 positionLife; vec4 velocitySeed; };
Buffers { STATE ("Particle state", Particle, readwrite) }

void Simulate() {
    Particle p = STATE(CG_ELEMENT);
    p.positionLife.xyz += p.velocitySeed.xyz * CG_TIME;
    STATE_WRITE(p);                     // this element: what a map kernel writes
}
```

```java
CgKernelProgram simulate = CgCompute.load("mymod:shaders/particles.compute").kernel("Simulate").program();
try (CgGlScope scope = CgKernelProgram.scope()) {
    simulate.use().buffer("STATE", stateBuffer).dispatch(count);
}
```

- **The shape** (`map`, `gather`, `append`, `scatter`, `image`, `general`) says what a kernel writes, and so which
  tiers run it. The compiler checks it against everything the kernel reaches and refuses what exceeds it by name.
- **Buffers and images are declared, not written**: the engine writes each kernel's declarations and accessors
  (`NAME(i)`, `NAME_WRITE`, `NAME_STORE`, `NAME_ADD`, `NAME_APPEND`, `NAME_INC`; `NAME_LOAD`, `NAME_WRITE` on an
  image) and assigns every binding.
- **Each kernel's source carries only the functions, `shared` variables, buffers and images it reaches.**
- **Subgroups** (`CG_SUBGROUP_ADD` and the rest) are the device's operations where it has them all and the work
  group through shared memory where not; a kernel never branches on support.
- **Every tier runs it**: as compute on V and G43; below compute (G40, G33) every shape but `general` is lowered to
  draws (transform feedback, blended points, fragment passes), and a general kernel runs its `#pragma fallback`; on
  the CPU tier, or wherever nothing else can, a Java body given with `kernel.cpu(...)`. A kernel some tier cannot
  run throws where its first dispatch is recorded **on every machine**, naming the tier and the construct, so the
  author's GPU finds what a Mac's would. One that needs compute says `#pragma compute_only`, and its caller asks
  `kernel.runs()`. A builtin newer than GLSL 3.30 is polyfilled exactly where a tier lacks it, or refused.

```java
particles.kernel("Simulate").cpu(d -> {
    CgCpuBuffer state = d.buffer("STATE");
    for (int e = d.first(); e < d.end(); e++) state.setFloat(e, 3, state.getFloat(e, 3) - d.time());
});
```

In a frame a kernel runs in a graph's compute pass (`recording.compute(...)`), on `CgGraphBuffer`s and storage images,
every barrier derived by the executor. **`CgGpuOps`** is the library every GPU-driven consumer would otherwise write:
fill, iota, copy, dispatch arguments, reduce, bounds, scan, compact, sort and histogram, and over a texture's mip levels
downsample and blur, dispatched into the caller's pass with the count fixed or read from the GPU, and the same answer
on every tier. Its package guide, `compute/CLAUDE.md`, has the bindings, the built-ins and what
is easy to get wrong; `render/graph/CLAUDE.md` the graph's half.
