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

struct Spark { vec4 positionLife; vec4 velocitySeed; };
Buffers { SPARKS ("Sparks", Spark, readonly) }   // a buffer a kernel wrote, read as SPARKS(i) (see below)

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

A run of draws the executor joins into one multi-draw (`render/graph/CLAUDE.md` § *Multi-draw*) binds every pass's
`CG_MULTI_DRAW` variant, in which `CG_INSTANCE_ID`, `CG_DRAW_INSTANCE` and `CG_VERTEX_ID` take each draw's bases from
its command instead of the two uniforms. They answer the same values, so a shader reading them never knows; one
reading `cg_InstanceBase` or `cg_VertexBase` directly does not compile in that variant.

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

### Reading a kernel's buffers

A material reads buffers a kernel wrote by declaring them in a `Buffers { }` block as a kernel does, every one
`readonly`, and reads element `i` as `NAME(i)` in either stage:

```glsl
#type none
Queue = "Geometry"

struct Spark { vec4 positionLife; vec4 velocitySeed; };   // declared before Buffers { }

Buffers {
    SPARKS ("Sparks", Spark, readonly)
    LIVE   ("Live",   uint,  readonly)                     // the live sparks' indices, compacted
}

Pass {
    struct v2f { float life; };
    void vertex(out v2f o) {
        Spark s = SPARKS(LIVE(CG_VERTEX_ID >> 2));          // quad n draws the nth live spark
        vec3 p = s.positionLife.xyz + vec3(CG_VERTEX_CORNER - 0.5, 0.0) * 0.1;
        o.life = s.positionLife.w;
        gl_Position = CG_MATRIX_MVP * vec4(p, 1.0);
    }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0, 0.6, 0.2, 1.0) * clamp(i.life, 0.0, 1.0); }
}
```

```java
sparkMaterial.buffer("SPARKS", sparks).buffer("LIVE", live);   // graph buffers; any thread
world.draw(CgMesh.quads(CAPACITY), sparkMaterial).indirect(count, 0, CgIndirect.INDICES, 6).at(x, y, z).submit();
```

| Context | A buffer is read as | Bound at |
|---|---|---|
| storage blocks (GL 4.3, the Vulkan device) | a `readonly` storage block | `CgBindingPoints.MATERIAL_BUFFERS_SSBO + i` |
| without (macOS's 4.1, GL 3.3) | a `usamplerBuffer`, a texel per 16 bytes of a struct | texture unit `samplers + i`, after the material's own |

- **The kernel's grammar and element rule**: a `float`, `int` or `uint`, their 2- and 4-vectors, or a struct of
  `vec4`, `ivec4` and `uvec4` only, since every context must read it. Upper-case names not starting `CG_`; at most 4.
- **Bound as a texture is**: `material.buffer(name, buffer)` holds for the draws captured after it, and the graph
  orders each draw after the pass writing the buffer. A history buffer reads its newest version.
- **Recorded draws only**: `captureBindings` (the world renderer, `CgImmediate`, a chunk) binds them; `bind()` binds
  none. Every declared buffer is bound before the first capture, which throws naming one that is not.
- **No `NAME_LENGTH()`**: a draw's count says how many elements it reads.
- **Without storage blocks**, samplers and buffers share the units below the engine's scene samplers, and a material
  needing more is refused at compile; a buffer of more texels than the context reads as one texture
  (`GL_MAX_TEXTURE_BUFFER_SIZE`, at least 65536) throws at capture.
- A vertex stage reading a buffer keeps its body in the generated depth and shadow passes.

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
| `Distortion` | How the surface bends what is behind it: an offset, applied to the scene once after the transparent pass | Never generated; at most one per shader. Below |

The `"Name"` tag sets the pass key dimension for the `ProgramKey` variant cache. Auto-assigned as `Pass0`, `Pass1`, … when absent.

#### The Emissive pass

What a material draws in its Emissive pass is the light it gives off: `CgWorldRenderer` writes it into a float
emission target, which bloom blurs and adds over the world (`docs/ENGINE_API.md` § *CgWorldRenderer*). Unity's,
Godot's and Unreal's emission is a material output added to an HDR scene colour; Minecraft's target is 8-bit, so here
it is a pass of its own. A codeless one on the Forward pass's blend is written by the Forward draw itself, as a second
output; any other is the mesh drawn again into the emission target after the transparent pass.

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
- **Codeless on one blend, it costs no draw of its own.** In a transparent material whose Emissive pass is codeless
  and blends as the Forward pass does, or adds (`Blend ONE ONE`) under a premultiplied Forward pass
  (`ONE ONE_MINUS_SRC_ALPHA`), the world renderer draws both in one draw: the Forward pass compiled with
  `CG_EMISSION_TARGET` also writes the glow at location 1, into the emission beside the target
  (`CgMaterialShaderCompiler.emissionMerge`, `CgPipeline.emissionTarget`). A body naming `CG_EMISSIVE_PASS` never
  merges, since one draw cannot be both passes. Below GL 4.0 both outputs share one blend, which is why the blends must
  agree.
- **With code and no `RenderState`**, it draws ONE ONE with no depth test and back faces culled. An authored state
  replaces all of it, including the cull, so list everything. ONE ONE is not `CgBlendState.ADDITIVE`, which is
  SRC_ALPHA ONE and adds nothing at alpha 0.
- **Hidden by the scene, not by a depth test.** The bloom target has no depth, so the compiler discards a fragment
  further than the scene's depth at its pixel, with slack for the depth buffer's precision (`CG_EMISSIVE_DEPTH_SLACK`,
  `CG_EMISSIVE_DEPTH_BIAS`, in `cg_env.glsl`). `DepthTest ALWAYS` turns that off for a shader that tests depth itself:
  a volume drawn on its back faces.
- Unlit and fogged additively whatever the material's tags. `CG_EMISSIVE_PASS` is defined in both stages, so a body
  it shares with the Forward pass can tell them apart (which keeps the two from merging):

```glsl
Pass {
    Tags { "LightMode" = "Forward" }
    void fragment(in v2f i, out vec4 fragColor) {
#ifdef CG_EMISSIVE_PASS
        fragColor = vec4(_GlowColor.rgb * mask(i.uv), 0.0);   // the glow alone
        return;
#endif
        fragColor = shade(i);                                  // the surface
    }
}
Pass { Tags { "LightMode" = "Emissive" } }                    // codeless: the body above, CG_EMISSIVE_PASS defined
```
- It takes the material's keywords, as the Forward pass does.
- **`CG_EMISSION` scales the glow**, a `vec3` in every pass: `_EmissionColor.rgb` (a `color` property) times
  `_EmissionStrength` (a `float`) where the shader declares them, times the draw's `.emission(scale)`
  (`CG_OBJECT_EMISSION`). The compiler multiplies an Emissive pass's output by it, unless the pass's code names
  `CG_EMISSION` itself. Declared with defaults of white and 1, a material glows as before; the unit is times the
  screen's white.

```glsl
Properties {
    _EmissionColor    ("Glow colour", color) = (1, 0.6, 0.2, 1)
    _EmissionStrength ("Glow strength", float) = 2.0
}
Pass { Tags { "LightMode" = "Emissive" } }                       // what Forward draws, times CG_EMISSION
Pass { Tags { "LightMode" = "Emissive" }                         // or authored, applying it where it wants
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(CG_EMISSION * mask(i.uv), 0.0); } }
```

- **A glow that draws nothing in the scene** is `crystalgraphics:shaders/emission_only.shader`: its Emissive pass
  blooms the mesh in `_EmissionColor` × `_EmissionStrength`, and its Forward pass writes no colour or depth.
- `-Dcrystalgraphics.post.debug=emission` draws the emission target over the frame; `=level<N>` one level of bloom's
  chain (`docs/DEBUG_FLAGS.md`).

#### The Distortion pass

What a material draws in its Distortion pass is where each pixel behind it takes its colour from: an offset in UV
units, a chromatic split, and the eye depth the haze starts at, packed by `CG_DISTORTION(offset, split, eyeDepth)`.
`CgWorldRenderer` adds every visible transparent draw's Distortion pass into one RGBA16F
target after the transparent pass, then applies it to the scene once (Unreal's distortion pass, HDRP's distortion
vectors). A heat haze or a shockwave writes an offset here instead of sampling `cg_SceneColor` itself.

```glsl
Pass { Tags { "LightMode" = "Forward" } ... }    // what the surface draws, if anything: a pure haze draws nothing

// No vertex(): the Forward pass's v2f, declarations and vertex function, with this pass's own after them
Pass {
    Tags { "LightMode" = "Distortion" }
    void fragment(in v2f i, out vec4 offset) {
        vec2 bend = haze(i.uv) * 0.02;            // UV units: 0.02 is 2% of the screen
        // split 0.3: red bends 1.3x as far, blue 0.7x; a flat surface starts where it is drawn
        offset = CG_DISTORTION(bend, 0.3, cg_LinearEyeDepth(gl_FragCoord.z));
    }
}

// A haze traced in a volume it draws the far wall of: it starts where its ray enters (fx_depth.glsl)
offset = CG_DISTORTION(bend * fade, 0.3 * fade, FX_EYE_DEPTH(ray, enter));
```

- **Offsets add**: overlapping hazes sum rather than each bending the last, and nothing seams where they meet. Fade one
  out by scaling its offset, never by alpha.
- **Splits add too**, held to 0.3; scale a fading haze's split with its offset.
- **The blend is the engine's**: whatever a Distortion pass's `RenderState` says, offsets and split add and the alpha
  keeps the nearest haze (`MAX`), so the pass sets no `Blend`.
- **A pure haze draws nothing in the scene**: give its Forward pass `ColorMask 0` and `DepthWrite OFF`, and the world
  renderer skips it (`CgRenderState.writesNothing()`), leaving the Distortion pass the whole cost.
- **A haze bends what sorts before it**, as each haze reading its own copy would. A draw that must not be bent by the
  hazes sorted before it (`Queue = "AfterDistortion"` for a material, `.afterDistortion()` for one draw) gets an apply of
  those hazes placed just before it in the transparent pass, cut to their rect; nearer hazes still bend it. Up to four
  such applies overlap on screen; past that it draws after the final apply, over nearer transparent draws.
  Blended after the apply, such a draw covers nearer transparent draws of other effects.
- **Hidden by the scene, not by a depth test**, as an Emissive pass is: a fragment behind the scene's depth is discarded
  before `fragment()` runs; `DepthTest ALWAYS` turns that off. With no `RenderState` it has no depth test and culls back
  faces.
- **The apply** mirrors UVs at the screen's borders and spreads red and blue by the split. It bends in whatever is
  behind the nearest haze, an opaque thing between it and the sky included, as Unreal's does. A bend whose farthest tap
  would read anything nearer than that haze is shortened to stop at that edge: the foreground is never pulled in, and
  the bend fades out at its silhouette. The eye depth a haze gives is what decides this, so give the depth where it
  starts, not its far wall's. A frame where nothing distorts records neither pass.
- **The glow bends too**: the emission bloom blurs is bent by the same offsets first, as a bloom taken from the
  distorted scene would be. Any post effect reading a side input of the scene does the same with `post.distorted(texture)`.
- `CG_DISTORTION_PASS` is defined in both stages; the pass takes the material's keywords. Unlit, unfogged, one output.
- `-Dcrystalgraphics.post.debug=distortion` shows the target (|offset| x 50 in red and green, the split in blue);
  `--mode=distortion` is the gate.

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
Pass {
    Tags { "LightMode" = "Forward" "Name" = "GBufferFill" }
    struct v2f { vec2 uv; vec3 normalWs; };
    // Inside the Pass, after struct v2f: the parser finds it nowhere else
    struct GBuffer {
        vec4 albedo  : RT0;   // → layout(location=0) out vec4 → GL_COLOR_ATTACHMENT0
        vec4 normal  : RT1;   // → layout(location=1) out vec4 → GL_COLOR_ATTACHMENT1
        vec4 pbr     : RT2;   // → layout(location=2) out vec4 → GL_COLOR_ATTACHMENT2
    };
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
| `occlusion.glsl` | `cg_occluded(pyramid, size, clipX..clipW, eye, lo, hi)`: whether a box hides behind a `CgGpuOps.depthPyramid` pyramid, everything as arguments. What `CgGpuOps.cull` and VFX Range test; `CgGpuOpsBodies.occluded` is the Java twin |
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

## Compute — `.compute` kernels

A `.compute` holds **kernels**: GLSL functions the GPU runs once per element. A kernel is written once and runs on
every context a player may have, with the same answer on each: as a compute shader where the context has them, and as
draws where it does not (macOS's GL 4.1, a GL 3.3 context). A Java body (`kernel.cpu`) is for debugging and tests:
shipped kernels carry none ([A Java body](#a-java-body)).
**`crystalgraphics:shaders/example.compute` is the reference**: every part of the format, annotated, compiled by the
tests on every target. Files live under `shaders/`, beside the `.shader` that draws what they write. Designing one
that runs well on all of them: [Designing for every tier](#designing-for-every-tier). Plan:
`plan/crystalgraphics/gpu-compute.md`; the package's internals: `compute/CLAUDE.md`.

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

- **Nothing above names a tier.** On GL 4.3 and the Vulkan device `Step` and the compaction run as compute shaders;
  on macOS and GL 3.3 they run as draws; the draw takes the count the GPU wrote on all of them (GL 3.3 reads it back
  first).
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
and so what it may reach and which tiers run it; the compiler refuses a kernel that reaches past its shape, naming the
kernel, what it reached and the line, helpers and macros included.

| Shape | Writes | Gets | Runs |
|---|---|---|---|
| `map` | its own element of each output buffer | `NAME_WRITE(v)` | every tier |
| `gather` | as `map`, reading other elements too | as `map` | every tier |
| `append` | as `map`, and appends elements | `NAME_APPEND(v)` | every tier |
| `scatter` | at computed indices: stores, adds, minima, maxima, counters | `NAME_STORE`, `_ADD`, `_MIN`, `_MAX`, `_INC` | every tier |
| `image` | its own texel of each output image | an image's `NAME_WRITE(v)` | every tier |
| `general` | anything compute does: `shared` memory, `barrier()`, subgroups, raw atomics, any index of any image | everything, and `NAME_DATA[i]` | compute only, unless given a fallback |

Only a `general` kernel may name `barrier()` and the memory barriers, `atomic*`, `imageLoad`/`imageStore`/`imageSize`,
`subgroup*` and `CG_SUBGROUP_*`, `CG_ATOMIC_*`, `shared` variables, or anything of a work group (`gl_LocalInvocationID`,
`CG_GROUP_ID`, `CG_LOCAL_INDEX`, …): a kernel run as draws has no work group.

#### Fallbacks, `compute_only`, keywords, engine buffers, includes

```glsl
#pragma kernel Reduce 256 general
#pragma kernel ReduceScatter scatter
#pragma fallback Reduce ReduceScatter    // what a tier without compute runs in Reduce's place, dispatched as Reduce is

#pragma kernel Sort 256 general
#pragma compute_only Sort                // never run without compute; its caller asks kernel.runs() first

#pragma cg_feature WIND                  // a keyword: each set asked for compiles once, as a material's variants
#pragma cg_use particle                  // an engine buffer: writable in a general kernel, read-only in the rest
#include "crystalgraphics:shaders/lib/rng.glsl"
```

- Only a `general` kernel takes a `#pragma fallback`, and its fallback is a lowerable kernel of the same file, bound by
  the same names.
- `compute_only` and `#pragma fallback` exclude each other: a fallback is a form without compute, which `compute_only`
  says there is none of.
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
| `append` | `NAME(i)`, `NAME_APPEND(v)`, `NAME_COUNT()` | the count is a separate `uint` the caller binds and zeroes |
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

Image `i` is bound at image unit `i`, and the texture bound must have the declared format. Formats are GLSL's layout
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
CgFrameBufferFormat r8ui = CgFrameBufferFormat.builder("voxels").color(0, CgTextureType.R8UI).build();
CgGraphTexture voxels = CgGraphTexture.requested("voxels", CgTextureDesc.volume(128, 96, 128, r8ui));
pass.dispatch(fill, 128, 96, 128).image("VOXELS", voxels);
step.dispatch(move, n).texture("_Wind", wind);
recording.update(voxels, 0, 0, 0, 32, 128, 96, 16, slab);   // 16 new slices from z 32: 128 * 96 * 16 bytes
int reads = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, voxels);   // a material's sampler3D
```

- One level and one colour attachment; nothing draws into a volume, so a raster pass into it throws.
- Its format's type is its filter: linear, nearest for an integer type, clamped at every face.
- Below compute an `image` kernel writes a volume in one draw, an instance a slice placed by a geometry stage's
  `gl_Layer`, and one loading the volume it writes is refused.
- In `textureLod` from a kernel, give the level: a kernel run as compute has no derivatives.

A buffer declares every use it is put to: `STORAGE` for a kernel or a storage block, `INDIRECT` for
`dispatchIndirect`'s arguments, `COPY` for a fill, update or copy; `VERTEX`, `INDEX` and `UNIFORM` for draws. **New
storage holds whatever the driver gives**: fill it, or write every element, before anything reads it.

#### Outside a frame

```java
try (CgImmediate.Compute run = CgImmediate.compute("bake")) {   // a graph of one compute pass, executed on close
    run.dispatch(bake, count).bind("CELLS", CgGraphBuffer.imported("cells", glBuffer, bytes));
}
```

This runs on every tier, as a graph does. `CgKernelProgram` is the direct form, **for compute alone** (V and G43): it
binds GL names itself and orders nothing after it.

```java
CgKernelProgram simulate = particles.kernel("Simulate").withKeywords("WIND").program();   // render thread
simulate.properties().set1f("_Drag", 0.1f);
try (CgGlScope scope = CgKernelProgram.scope()) {           // saves what a dispatch changes
    simulate.use().buffer("STATE", stateBuffer).frame(constants).dispatch(particleCount);
}
CgGL.cgBufferBarrier(stateBuffer, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // the reader barriers
```

#### Before the first dispatch

A kernel compiles at its first dispatch, on the render thread. `prepare()` starts it earlier — at load, on a loading
screen — so that frame finds it compiled:

```java
CgCompute particles = CgCompute.load("mymod:shaders/particles.compute");
particles.kernel("Simulate").prepare();                 // each kernel and keyword set the effect dispatches
particles.kernel("Simulate").withKeywords("WIND").prepare();
```

- Where the driver links on its own threads (`KHR_parallel_shader_compile`), the link runs while frames go on; the
  dispatch that takes the program waits for what is left (`compute.compileWait`, counted by `compute.compile-waits`).
- Once the driver has linked it, a frame boundary makes it ready and binds it once (`compute.finishPrepared`, about
  1 ms a frame at most): on NVIDIA a program's first bind costs up to 1.6 ms, which would otherwise land on its first
  dispatch.
- A lowered kernel's passes are built at once; a Java body needs nothing.
- On a Vulkan device a host keeps shaderc's output and the pipeline cache across launches
  (`CgCacheDirectory`, `crystalgraphics/cache/` under the game directory): a second launch compiles roughly half as
  long.

### Drawing what a kernel wrote

The whole workflow, from a kernel to culled draws joined into one call, with recipes and the design rules:
[`GPU_DRIVEN_RENDERING.md`](GPU_DRIVEN_RENDERING.md).

| A kernel wrote | A draw reads it | Tiers |
|---|---|---|
| a count | `.indirect(count, offset, mode, factor)` on a world or chunk draw | every tier; G33 reads the count back first, a stall counted as `buffer.readbacks` |
| an image | the graph texture, sampled by a material; a volume as a `sampler3D` | every tier |
| a buffer of records | `NAME(i)` in a material declaring it in `Buffers { }`, bound with `material.buffer(name, buffer)` | every tier; below GL 4.3 as a buffer texture |
| object records, `CgInstanceKind.OBJECT`'s 48 floats each | `.objects(records, first, n)` on a chunk draw: instance i reads record `first + i` through `CG_OBJECT_DATA`, in any material; or `.instances(records, count)` on a world draw, culled on the GPU (below) | every tier; below GL 4.3 as a buffer texture |

```java
// A count: a quad per live spark, or a mesh instanced once per element
world.draw(CgMesh.quads(capacity), sparks).indirect(alive, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
world.draw(billow, smoke).indirect(alive, 0, CgIndirect.INSTANCES, 1).at(x, y, z).bounds(box).submit();

// Object records: a rock per record a cull kept, as many as it counted
chunks.draw(rocks.pipeline(CgInstanceKind.OBJECT), bindings, rock).objects(visible, 0, capacity)
      .indirect(visibleCount, 0, CgIndirect.INSTANCES, 1);

// An image: written by a kernel, sampled by the material's first sampler in a pass recorded after it
CgFrameBufferFormat rgba8 = CgFrameBufferFormat.builder("heat").color(0, CgTextureType.RGBA8).build();
CgGraphTexture heat = CgGraphTexture.transientTexture("heat", new CgTextureDesc(256, 256, rgba8));
CgComputePass shade = rec.compute("heat");
shade.dispatch(heatKernel, 256, 256, 1).image("HEAT", heat);
shade.end();
int bindings = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, heat);
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
- Outside a graph, `CgReadback.buffer(glBuffer, offset, size, sink)` and `CgReadback.pixels(fbo, x, y, w, h, type,
  sink)` do the same on GL names.

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
recording.raster(shadowMap, ...);
```

**Mark every pass that fits.** `async()` is opt-in, not optional: a pass that fits and is left in order costs the
frame its whole time, where async it costs little or nothing. A pass fits when all three hold:

1. **It is all compute.** A pass with a dispatch below compute runs in order anyway.
2. **It is big enough to hide**: thousands of elements, or dispatches with barriers between them (a simulation step,
   a sort, a scan, a cull, a reduction's last levels). A pass of a few hundred elements costs more to hand between
   queues than it hides.
3. **Drawing runs between it and its first reader** in the frame: a step at the opaque stage drawn at the transparent
   one, a cull or sort recorded ahead of passes that do not use it. Recording order and stage boundaries do not
   matter (below); only what the frame does in between.

Leave it off a small pass, a pass whose reader is the next thing the frame does, and heavy compute beside heavy
compute, which only fight for the same units.

- **Where the device has a compute queue** (`CgCapabilities.asyncCompute()`: the owned Vulkan device, and Minecraft
  26.2's, whose own compute queue Minecraft leaves unused), the pass runs on it, after every step placed before it.
  The steps after it that touch nothing it reads or writes run beside it, and the first that does waits for it, in
  this stage or a later one of the frame; a callback waits for all of it.
- **Its waits cross stages for what only the graph reaches**: graph buffers (not imported ones) and transient
  textures. An imported, current or requested texture or an imported buffer the host may touch outside the graph, so
  work on one is waited for at the end of its stage. The frame's end waits for everything.
- **The builder places it, not the recording**: the pass and what it reads go as early as the graph allows, and what
  reads its results as late, so the drawing recorded after its consumer still runs beside it. On every device: the
  order is one the reads and writes allow, so the result is the same.
- **Everywhere else it runs in order**, with the same result: GL, a Minecraft device with no compute queue of its own,
  and a pass with a dispatch below compute.
- **On Minecraft's device an async pass may not use Minecraft's own textures** (its main target, the lightmap): only
  Minecraft's graphics queue may, and the pass throws naming the texture.
- `--mode=async-compute` measures it: beside eight 1080p blurs (2.5 ms), half of 1 ms of fill-bound drawing disappears
  on an RTX 4070 SUPER; a sort in one stage with the drawing in the next overlaps the same way.
- `-Dcrystalgraphics.graph.asyncAll=true` sends every pass that can go async, which is how the gates check the waits.

### Every tier

| Tier | Context | A kernel runs |
|---|---|---|
| `V` | CrystalGraphics' Vulkan device | as compute |
| `G43` | GL 4.3, or 4.2 with the compute and storage-image extensions | as compute |
| `G40` | GL 4.0 and up without compute: macOS's 4.1 | lowered; else its fallback lowered; else not at all (`runs()` is false) |
| `G33` | GL 3.3 | as `G40`, a count a draw takes read back first |
| `CPU` | forced | its Java body, else its fallback's |

A context runs at the best tier it has; `-Dcrystalgraphics.compute.tier=V|G43|G40|G33|CPU` forces one, and forcing one
the context lacks throws naming what it lacks. `kernel.form()` answers how this context runs a kernel (`COMPUTE`,
`LOWERED` or `CPU`, and which kernel: itself or its fallback).

**Lowered**, a `map` or `gather` kernel is a fragment pass over a render target laid out as the buffer's words, read
into the buffer on the GPU; an `image` kernel is a fragment pass into the image; what an `append` kernel appends is
captured by transform feedback; a `scatter` kernel is points blended into a target. What follows from that:

- Every pass of a dispatch reads what the buffers held before it.
- A scatter's add, minimum and maximum are blends: a float sum is exact to 2^24 a bin, and a counter's `NAME_INC` or
  `NAME_ADD` **whose result is used** is refused, since a blend answers nothing.
- Refused, by name: an appended element wider than 64 words, a cube image, an SNORM image written, and reading two
  levels of the texture a kernel writes. Reading one level while writing another works.
- In a frame graph, what a dispatch writes stays in its target while only lowered kernels read it, and lands in the
  buffer through one read-back (15-70 µs of driver CPU) before anything else reads it: a draw, a CPU body, a fill or a
  copy, a callback, the end of the execution. Ops chained in one graph land only what leaves them; a dispatch outside a
  graph lands at once.

**Every tier is asked at a kernel's first record, on any machine**: the first `dispatch` of it, or its first
`program()`, asks the form G43, G40 and G33 would choose, and compiles the builtins it reaches at each tier's GLSL
(4.20, 4.00, 3.30). A kernel a Mac would refuse throws on the author's machine too, naming the tier and the construct:

```
[mymod:shaders/bins.compute] kernel Bin cannot run at tier G40 …, which has no compute: general and uses shared
memory (cache). Give it a lowerable #pragma fallback, or declare it '#pragma compute_only Bin' and ask
kernel.runs() before dispatching it
```

Two ways out, for a `general` kernel: a lowerable `#pragma fallback`, or `#pragma compute_only` and a `runs()` check
where it is used. A Java body is not one: no player's tier runs it ([A Java body](#a-java-body)).

```java
CgKernel sort = kernels.kernel("Sort");                     // #pragma compute_only Sort
if (sort.runs()) pass.dispatch(sort, count).bind("KEYS", keys);   // false below compute
else sortAnotherWay(keys);
```

**Builtins newer than GLSL 3.30** (`bitCount`, `findMSB`, `uaddCarry`, `umulExtended`, `fma`, `frexp`,
`packUnorm4x8`, `packHalf2x16`, …) are called through an exact polyfill wherever a tier lacks them, a dozen
instructions for one. One no polyfill gives exactly (`textureGather`, `textureQueryLevels`, a `double`) is refused on
the tiers below its version. A function of the file's own named as a builtin keeps its own body.

#### A Java body

**For debugging and tests, never shipped.** Forcing the CPU tier (`-Dcrystalgraphics.compute.tier=CPU`) runs a
kernel's Java body: a breakpoint inside a kernel, or a reference to check a GPU form against. A shipped kernel carries
none: below compute it runs lowered, as its fallback lowered, or not at all behind `runs()`.

```java
particles.kernel("Simulate").cpu(d -> {                     // every keyword set of the kernel shares it
    CgCpuBuffer state = d.buffer("STATE");
    int pos = state.field("positionLife").word(), vel = state.field("velocitySeed").word();
    float step = d.property("_Step");
    for (int e = d.first(); e < d.end(); e++) {
        for (int c = 0; c < 3; c++) state.setFloat(e, pos + c, state.getFloat(e, pos + c) + state.getFloat(e, vel + c) * step);
    }
});
```

- A `map`, `gather`, `append` or `image` body runs on several worker threads at once, each over its range
  (`d.first()` to `d.end()`), writing its own elements only. A `scatter` or `general` body runs once, on one thread,
  over every element.
- `CgCpuDispatch`: `buffer(name)`, `image(name)`, `texture(name, level)` (a sampler property's level, as `texelFetch`
  reads it), `property(name[, component])`, `propertyInt(name)`,
  `keyword(name)`, `time()` (`CG_TIME`), `count(axis)` and `x(e)`, `y(e)`, `z(e)`; to append,
  `int i = d.append("SPAWNED")` then write element `i` of `d.appended("SPAWNED")`, and `appendCount(name)` is
  `NAME_COUNT()`.
- `CgCpuBuffer`: `getFloat`/`getInt`/`setFloat`/`setInt(element[, word])`, `addInt`, `addFloat`, `min*`/`max*`, and
  `field(name)` for a struct member's words. `CgCpuImage`: `loadFloat`/`loadInt(x, y, z, c)`, `store`/`storeInt`.
- The body runs over CPU copies of the bound buffers, read back once and kept while nothing else writes them; a
  buffer written outside the graph is only seen fresh when bound as an imported graph buffer.

### Ops: `CgGpuOps`

What every GPU-driven consumer would otherwise write: dispatched into the caller's compute pass, the same answer on
every tier. Scratch is graph transients from the pass's recording (`CgRecording.scratch`), the same handles each frame
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
| `depthPyramid(recording, depthOf, constants, pyramid)` | a target's depth as eye depth, each level the farthest it covers: what a cull tests against |
| `cull(pass, cull, instances, [first,] count, out, counts, word)` | instances of one mesh culled as `CgWorldRenderer` culls a draw (frustum, level by screen height, the pyramid's depth): each level's kept object records, and how many; from record `first` for one range of a shared buffer |
| `cull(pass, view, sets, out, counts)` | every set of a `CgCullSets` culled at once, each as the op above with order kept: two dispatches per instance buffer and one sort, however many sets |

- **A count is fixed or a word on the GPU** (`CgGpuCount.of(n)`, `CgGpuCount.at(buffer, word, capacity)`). A GPU count
  dispatches the capacity and every kernel stops at the count it reads, so no op needs it on the CPU.
- Buffers hold 32-bit words with `STORAGE`; an `Element` (`UINT`, `INT`, `FLOAT`) says how to read them. Nothing past
  the count is written, and an op handed one buffer to read and write throws.
- **The same bits on every tier**: integers exactly, a float sum in the same tree order (sixteen at a time, so not a
  plain loop's bits), a sort stable everywhere. Where compute runs, the sort is FidelityFX Parallel Sort's and a
  histogram of at most 1024 bins counts in shared memory, with the same answer.
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
- The pyramid is `CgGpuOps.PYRAMID_FORMAT` with mips, the size of the target it reads; one built from this frame's
  depth hides what is behind the host's world as drawn so far.
- Where draws join and the GPU writes the commands (compute, and G40 with indirect draws), the levels are one
  multi-draw call (`render/graph/CLAUDE.md` § *Multi-draw*).
- Its gate is `--mode=gpu-cull`: the same picture as the world renderer's CPU cull, byte for byte, on every tier, by
  hand and as a world draw.

**`lib/rng.glsl`** is a counter-based generator (PCG4D): `cg_rng4(seed, element, step, stream)`, any element drawing
its own numbers in any order, integer-exact on every tier, with `CgRng` giving the same bits in Java. Key an element
by an id it carries, never its slot, or compaction reshuffles its numbers.

**What each op costs**, GPU ms on an RTX 4070 SUPER at a million elements and a 1920x1080 chain (the harness's
`gpu-ops-cost` prints it for any machine):

| Op | compute (G43) | lowered (G40, G33) |
|---|---|---|
| fill, iota, copy | 0.02-0.04 | 0.08-0.09 |
| reduce | 0.03 | 0.25 |
| scan, compact | 0.08, 0.11 | 0.39, 0.64 |
| bounds | 0.29 | 1.3 |
| histogram, 64 bins | 0.01 | 0.41 |
| sort, 32 bits | 0.42 | 8.0 |
| downsample, blur | 0.03-0.53 | 0.08-0.45 |

Lowered, an op run alone lands its result once, a `glReadPixels` costing this driver 15-70 µs of CPU, and its CPU is
0.1-0.5 ms; a 32-bit sort takes 3.2 ms of CPU, its eighty dispatches' draws rather than landing. The CPU tier takes
3-19 ms for the buffer ops and 360 ms for a 32-bit sort. **Below compute, sort fewer bits**: a depth key quantised to
16 bits sorts in four passes, not eight.

### Designing for every tier

Write for the Vulkan device and GL 4.3, and the same file runs on macOS's GL 4.1 and a GL 3.3 context. What changes
below compute is what a kernel may do and what a dispatch costs. The every-tier check ([Every tier](#every-tier))
refuses the first on the author's machine; the second is the design's to answer.

**1. Pick the narrowest shape.** The first six rows run on every tier as written.

| The kernel… | Write it as |
|---|---|
| updates each element from itself | `map` |
| reads other elements: neighbours, a lookup | `gather` |
| emits zero or more new elements: spawning, trails | `append` |
| writes at a computed index: binning, splatting, counting | `scatter` |
| writes each texel of an image | `image` |
| sums, prefix-sums, compacts, sorts, bins, takes bounds | a [`CgGpuOps`](#ops-cggpuops) op, not a kernel of your own |
| uses `shared` memory, `barrier()` or subgroups for speed | `general`, with a lowerable kernel for the same result as its `#pragma fallback` |
| has no lowerable algorithm | `general` and `#pragma compute_only`, the feature behind `kernel.runs()` |

```glsl
#pragma kernel Bin 256 general           // bins in shared memory: what compute runs
#pragma kernel BinScatter scatter        // the same counts by blended adds: what G40 and G33 run
#pragma fallback Bin BinScatter
```

A kernel with no lowerable form is `compute_only`, and below compute the feature is absent or done another way: a
Java body (`kernel.cpu(...)`) runs only when the CPU tier is forced, for debugging.

**2. Lay records out in `vec4`s.** Below `general` a struct holds `vec4`, `ivec4` and `uvec4` only: pack scalars into
lanes rather than adding fields.

```glsl
struct Particle {
    vec4  positionLife;   // xyz position, w life left
    vec4  velocityAge;    // xyz velocity, w age
    uvec4 idKind;         // x a stable id, y the kind, zw spare
};
```

**3. A dispatch reads the buffers as they were before it.** Lowered, every pass reads what the buffers held before the
dispatch; on compute, reading an element another invocation writes is a race. State that advances is a history buffer
(`IN` reads the newest version, `OUT` writes the next), and work that needs another's result is a second dispatch,
which the graph orders.

**4. A scatter's add is a blend.** Below compute `_ADD`, `_MIN` and `_MAX` blend into a float target: a count is exact
to 2^24 a bin, and a counter whose answer is used is refused. To claim a slot, append; to number survivors,
`CgGpuOps.compact`.

```glsl
uint slot = FREE_INC(0);  OUT_STORE(slot, p);   // refused below compute: a blend answers nothing
SPAWNED_APPEND(p);                              // every tier
```

**5. Count dispatches, not elements.** Below compute a dispatch is draws: an op takes 0.1-0.5 ms of CPU where GL 4.3
takes 0.01, and up to 40 times the GPU time ([what each op costs](#ops-cggpuops)).

- One kernel writing several buffers beats a kernel for each: outputs of one layout share a pass, up to eight.
- Chain kernels in one graph, and fill, copy, draw or read a buffer at the chain's end: each buffer leaving a chain of
  lowered kernels is a read-back.
- Sort the bits the key has: `sort(pass, 16, …)` is half a 32-bit sort's passes, and a 32-bit sort of a million keys
  lowered takes 8 ms of GPU.
- At G33 a GPU count a draw takes is read back first: a stall for each such draw.

**6. Scale the work by a budget.** An effect sized for the author's GPU runs on a Mac at a fraction of the speed. A
`CgGpuBudget` times the passes charged to it on the GPU and answers a scale that holds them inside its milliseconds:
multiply what is spawned or simulated by it. Size capacity by the form, since a budget scales work, not storage.

```java
static final CgGpuBudget SPARKS = CgGpuBudget.define("sparks", 1.5f);        // 1.5 ms of GPU a frame
CgComputePass step = recording.compute("sparks.step").timed(SPARKS);       // charged to it: raster passes too
int spawned = Math.round(wanted * SPARKS.scale());                          // in [0.1, 1]
int capacity = kernel.form().how() == CgKernelForm.How.COMPUTE ? 1_000_000 : 100_000;
```

- It starts at the tier's share (1 as compute, 0.5 lowered, 0.25 on the CPU tier), drops at once when over and rises
  after a run well under; what it measures lags the work by a few frames and never waits for it.
- An `async()` pass on a device with a compute queue runs beside the timer and is not counted.

**7. Integers where the answer must match.** Integers and `cg_rng` give the same bits on every tier and in a Java
body; floats agree to a rounding. A decision, a count or a seed that must match across machines is integer math or
`cg_rng`, keyed by an id the element carries, never its slot.

**8. Run it on the tiers you do not have.**

```bash
# your GPU, forced to each tier's form: G43, G40, G33, CPU
./gradlew :gl-debug-harness:runHarness --args="--mode=<scene>" -Dcrystalgraphics.compute.tier=G40
# a context that lacks compute: Mesa as macOS's GL 4.1, or a bare 3.3 (gl33)
./gradlew :gl-debug-harness:runHarness -Pharness.downlevel=mac41 --args="--mode=<scene>"
```

A forced tier keeps NVIDIA's extensions and lenient compiler; only the downlevel run has a Mac's limits.

### Easy to get wrong

- **The shape is checked against everything a kernel reaches**, helpers and macros included: a `map` calling a helper
  that calls `barrier()` is refused with the kernel, the name and the line.
- **A struct comes before `Buffers { }`**: the generated declarations stand where the block stood.
- **A `general` kernel has no early return**: it checks `CG_IN_RANGE` after its barriers.
- **An append buffer's count is the caller's**: bound with `.counter(name, buffer, offset)` and zeroed before the
  dispatch (`recording.fill`).
- **An unbound buffer, count or image** a kernel uses throws at `pass.end()`, naming the pass, the kernel and the
  binding.
- **`vec3`** is refused as a property and, below `general`, as a buffer element.
- **A `CgKernelProgram` dispatch orders nothing after it**, and does not run below compute; a graph's dispatch is
  fenced by the executor and runs everywhere.
- **Limits are the device's, checked at `program()`**: the size per axis, invocations, shared memory.
- **New graph buffer storage is not zeroed.**
- **A CPU body's copy is only as fresh as the graph knows**: a raster pass writing a storage buffer is not seen.

### Debugging and testing

```java
String glsl = kernel.glsl();                // the source it compiles from on this context, includes unexpanded
CgKernelForm form = kernel.form();          // COMPUTE, LOWERED or CPU, and which kernel runs
```

- A kernel that fails to compile throws with the driver's log and the emitted source, numbered. One that fails to
  parse throws `CgShaderParseException` from `CgCompute.load`, naming the file, the kernel and the line.
- **Checked mode**, `-Dcrystalgraphics.compute.checked=true`: every buffer and image access a kernel run as compute
  makes is bounds-checked, one out of range is skipped, and the first of each dispatch is logged a frame or two later,
  once a place (`CgComputeCheck.reported()` lists them):

  ```
  [crystalgraphics] compute check: mymod:shaders/bins.compute, kernel Bin, line 24: BINS_ADD at 64, past BINS's 64 elements
  [crystalgraphics] compute check: mymod:shaders/heat.compute, kernel Paint, line 29: HEAT_WRITE at (32, 8), outside HEAT's 32x32 (256 times in one dispatch)
  ```

  Compute tiers only: a lowered kernel writes its own element, and a Java body's buffers throw on their own.
  `NAME_DATA[i]` and appends are not checked. A debugging switch: every access tests its index.
- **What a buffer holds**, field by field: `CgBufferInspector` reads any buffer a compute pass binds, after that pass,
  and decodes it through the kernel's own declaration, on every tier and without a stall (`render/graph/CLAUDE.md`
  § *Inspecting a buffer*):

  ```java
  CgBufferInspector.watch(true);
  for (CgBufferInspector.Site site : CgBufferInspector.sites()) System.out.println(site);
  // sparks after particles.step: 4096 x Spark, 32 bytes each (mymod:shaders/particles.compute Step, STATE)
  CgBufferInspector.read(site, 0, 16, read -> System.out.println(read.value(0, site.decl().field("positionLife"))));
  ```
- An asset reload (`CgAssetReloader`) re-reads every `.compute`; `CgCompute.load(path).reload()` re-reads one.
- `-Dcrystalgraphics.compute.tier=G40` runs a kernel as a Mac would on any machine; `G33` with
  `-Dcrystalgraphics.shaderBuffer.tier=TBO` as a GL 3.3 context.
- `-Pharness.downlevel=mac41|gl33` runs the harness on Mesa shaped as a real GL 4.1 or 3.3 context, without the
  extensions a forced tier on a desktop driver still has (`gl-debug-harness/AGENTS.md`).
- `CgComputeSelfTest` ships: one kernel per shape, every result worked out in Java.
  `-Dcrystalgraphics.compute.selfTest=true` logs its verdict on any client a frame or two after its first, never
  waiting on the GPU (Minecraft's Vulkan device included), and the harness's
  `compute-tiers` runs it. `gpu-ops` checks every op against Java; `shader-compile-audit` compiles every shipped
  kernel, and every lowered pass of it, on the driver.

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=compute-tiers --seconds=5" -Dcrystalgraphics.compute.tier=G40
./gradlew :gl-debug-harness:runHarness --args="--mode=gpu-ops --seconds=120" -Pharness.downlevel=mac41   # ~1 min on Mesa
./gradlew prodSmoke -PcgTargets=<labels> -PcgSmokeProps=crystalgraphics.compute.selfTest=true
```
