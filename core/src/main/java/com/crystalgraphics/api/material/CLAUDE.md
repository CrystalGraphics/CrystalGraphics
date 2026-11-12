# api/material — CrystalShader Material API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

User-facing entry point for the CrystalShader material pipeline. Provides `CgMaterial`
for loading, compiling, and binding `.shader` files. The canonical vertex format for
spatial materials is `CgVertexFormat.SPATIAL` (defined in `api/vertex/`).

This is the top of the CrystalShader material stack.

## Class Map

| Type | Role |
|------|------|
| `CgMaterial` | A material: a shader with its state, keywords and default values, shared and never changed once made (Unreal's material and material instance constant). `load(path)`, `load(path, state)`, `load(key)`, `of(definition)`, `of(definition, drawKeyword)`, `fromSource(source[, state])`, `fromShader(asset)`: the same material for the same arguments, none the caller's to free. `keyword(name, on)` is another material of its family, made once and cached by keyword mask (no allocation once made); `instance()` a `CgMaterialInstance`. Reads: `properties()`, `declaredKeywords()`, `isKeywordEnabled`, `permutations()`, `declaresBuffer`, `names`, `state()`, `precache()`, `lastCompileError()`, `getMaterialId()`. What the renderer asks (pipelines, bindings, passes, render state) is `CgMaterialProxy.of(material)`'s, engine-internal |
| `CgMaterialInstance` | Values of its own over a `CgMaterial` (Unreal's `UMaterialInstanceDynamic`): `CgMaterialValues`'s setters called on it directly (`set1f`, `vec4`, `colorARGB`, `sampler`, `array`), `buffer(name, buffer)`, `delete()`. Its programs and keywords are its material's; `properties()` answers its own values |
| `CgMaterialInterface` | What a draw takes: a `CgMaterial` or a `CgMaterialInstance` (Unreal's `UMaterialInterface`). `material()` is the material whose programs it draws with |
| `CgMaterialInstances` | An instance per keyword variant of one material, made at first ask and kept: for a drawer whose keywords follow its values (canvas glass, a colour region, the post composite) |
| `CgParameterCollection` | Values every material reads, set once (Unreal's material parameter collection): `of(namespace)`, `scalar(name, default)` and `vector(name, argb)` answer typed handles, `set`/`get` on the render thread without allocating. Each collection is placed in the `globals` engine buffer (`CgParameterCollectionBuffer`) at its first use, scalars packed by four then vectors, in definition order; `glsl()` is the `#define CG_GLOBAL_<ns>_<name>` lines the compiler appends after `env/buffer/globals.glsl`, and `upload()` the world renderer's once a frame. A collection placed after a shader compiled against it recompiles that shader (`CgAssetReloader.changed` on the provider's `generatedPath()`). `docs/SHADERS.md` § *Global parameters* |
| `CgParameterCollections` | The `parameters` kind's compiler (`CgBuiltInCompilers`): an asset declares its collection, `base` and `overrides` applied; compiled again with the same layout it answers the same collection, a changed layout throws |
| `CgMaterialState` | A material's render state where it is not its shader's (Unreal's base property overrides): `builder().blend(CgBlendMode).clip().cull(..)/twoSided().depthWrite(b).separateTranslucency()`; the last orders a translucent draw after every other (`CgWorldRenderer.add`). Blend and clip are its shader asset's (`changesProgram`, `programKey`); cull and depth write are applied per pass by `CgMaterial.getPassRenderState` through `onPass`. `CgMaterial.load(path, state)`, `fromSource(source, state)`, `state()`. `docs/SHADERS.md` § *A material's state* |
| `CgBlendMode` | `OPAQUE`, `ALPHA`, `PREMULTIPLIED`, `ADDITIVE`, `MODULATE`, `SUBTRACT`: each the `CgBlendState` of the `Blend` line it stands for (`state()`) |
| `CgRenderPassVariant` | Public enum bridging orchestrators to per-pass LightMode routing. `FORWARD("Forward")`, `DEPTH("Depth")`, `EMISSIVE("Emissive")` (the world's bloom; `CgMaterial.hasEmissivePass()`), `DISTORTION("Distortion")` (offsets the world renderer applies to the scene; `CgMaterial.hasDistortionPass()`). `lightModeName()` returns the canonical LightMode tag value. Used by `pipeline(CgRenderPassVariant, CgInstanceKind)`, `getPassRenderState(CgRenderPassVariant)`, and `hasDepthPass()`. No shadow-caster variant: the world's shadows are ray-traced from each pixel (`CgWorldLights`), so no material draws from a light's side. |
| `CgAttachedBuffer` | Immutable descriptor for an engine token's buffer, declared for a compile by `#pragma cg_use`: an SSBO or a UBO. Created via `CgAttachedBuffer.of(buffer, macroName)` (SSBO, STD430) or `CgAttachedBuffer.of(CgUniformBuffer)` (UBO, STD140). `isUbo()` returns `true` for UBO entries (macroName is null). SSBO fields: `buffer`, `macroName`, `structName` (= `format.getGlslName()`), `ssboArrayName` (`_cg_{lowerFirst}Arr`), `tboGetterName` (`_cg_get{structName}`). UBO entries: only `buffer` is set; macroName/structName/ssboArrayName/tboGetterName are all null. Block/sampler/UBO block name is always `buffer.getName()` — required for `wireShader()`. |
| `CgMaterialRegistry` | Singleton load/reload/delete lifecycle manager. `get()` returns the singleton. Every material is cached here by its shader asset and state (`of(asset, state)`); `getOrCreate(String)` / `getOrCreate(CgMaterialKey)` are a path's with the shader's state. `reloadAll()` marks every material's shader asset dirty; each material and instance made from it rebuilds its values from the new parse at its next capture, keeping each value whose property kept its name and type. `deleteAll()` deletes every material (each with its keyword variants), then the shader registry frees the programs. Registered for teardown in `CgEngineLifecycle.shutdown()`. `getOrCreate(definition, drawKeyword)` is a `CgMaterialDefinition`'s material (`CgMaterial.of(definition)` with none): one a definition and draw keyword, by identity, its archetype under `definition.state()`, its keywords, constants and textures with their samplers, a texture made in code bound as the object (`Texture.resolve`), each file made as its `texture` asset says (`texture(path)`: sRGB or linear, mips, a `.hdr` as RGBA16F, `cube: true` as six stacked faces), each texture bound only to its kind's sampler (a cube's `samplerCube`, an array's `sampler2DArray`, a volume's `sampler3D`); `release(definition)` deletes an old generation's |
| `CgMaterialKey` | `@Desugar record` wrapping a resource-path string. `of(String)` factory. Value equality. Used as a typed alternative to raw strings. |
| `CgMaterialDefinition`, `CgMaterialDefinitions` | A `material` asset compiled (`CgMaterialDefinitions.register()` makes it the kind's compiler): its parent chain merged, every `values`/`textures` key and keyword checked against the archetype, its `CgMaterialPermutation` (interned program key: archetype, keywords, blend class, clip, shading; a value-only child shares its parent's), constants, row layout (`CgMaterialRowLayout`), `Draw` state, domain and shading model. `route(p)` and `requireRow(p, who)` (a key refused on a non-row property), `requireDomain(d, who)`, `state()`. `CgMaterialDefinition.of(id, shader)` writes the file's tree (`tree()`) and compiles it (`build()`); `CgMaterialDefinitions.derive(parent, overrides)` is a child made in code, interned per parent generation (a `WeakHashMap` on the definition) by its overrides in canonical form (floats by bits, keywords sorted) |
| `CgMaterialArchetypes` | Where an archetype is read: `FILES` through `CgIO`, a kit's `<ns>:shaders/<name>.shader` falling back to the engine's `crystalgraphics:shaders/vfx/archetype/<name>.shader` (`resolve`); its domain from `Tags { "Domain" = ... }` |
| `CgMaterialManifest` | A move's permutations by slot-kind keyword and half size: what a precache request builds (`CgPipelinePrecache.request(p -> p.add(manifest))`) |
| `CgMaterialRoute`, `CgMaterialRow` | The three routes a value takes (constant, row, global) and one draw's row of a definition, written by slot |
| `CgShadingModel`, `CgShadingModels`, `CgMaterialDomain` | Shading models by registry (`unlit`, `lit`, `subsurface`, and a kit's `registerKit(namespace, declarations)`, or `registerKit(namespace, folder)` reading the kit's `shading.yaml`, `shading:` name to `index`, `base`, `keyword`); `SURFACE`, `POST`, `DECAL`, `TEXT`, `UI`, `GLOBAL`, read from a file's `#domain` line (`ofDirective`) or its old `"Domain"` tag |
| `CgVertexFactory` | What draws a `#domain surface` material (Unreal's vertex factory). A factory is a file, `VertexFactory Name` resolving `crystalgraphics:shaders/factories/Name.factory` and `ns:Name` its namespace's, read at first use (`material.parse.CgFormatTemplates`, which has the file's shape); the engine's seven are files (`Mesh`, `Procedural`, `Sprites`, `MeshParticles`, `Tube`, `TubeClosed`, `TubeVolume`). This class registers one built in Java (`register(builder(name).vertexType(..).fill(..)...build())`, `get`, `all`), found before a file. A factory brings its engine buffers, declarations, properties its draws bind, material tags and a parameters variable (`cg_Particle`) whose fields `surface()` names are passed to the fragment, one interpolator each (`_cg_in_` v2f fields). `v.normal` is world-space. A name registered twice throws |
| `CgRenderQueue` | Final utility class (not an enum). Static `int` constants: `BACKGROUND` (1000), `GEOMETRY` (2000), `ALPHA_TEST` (2450), `TRANSPARENT` (3000), `AFTER_DISTORTION` (3500: transparent, drawn after the distortion apply), `OVERLAY` (4000). Threshold constants: `ALPHA_TEST_THRESHOLD` (2450), `TRANSPARENT_THRESHOLD` (2500), `AFTER_DISTORTION_THRESHOLD` (3500), `OVERLAY_THRESHOLD` (4000). `fromName(String)` maps PascalCase or SCREAMING_SNAKE names to int value; throws for unknown names. |
| `package-info.java` | Package-level Javadoc describing the material API and lifecycle contract. |

**Shader compilation**: all compile pipeline logic lives in `material/CgMaterialShader` + `CgMaterialShaderRegistry`. See [`material/CLAUDE.md`](../../material/CLAUDE.md).

## Key API

### Drawing a material in the world

A material does not own the frame block or the object buffer: a recorded pass carries its constants, and
the executor binds both. Into Minecraft's world, submit to `CgWorldRenderer` (see
[`render/world/CLAUDE.md`](../../render/world/CLAUDE.md)); anywhere else, draw it in a raster pass of your own (`pass.draw(mesh, material)`, `CgRenderer.executeNow`).

```java
CgWorldRenderer.get().draw(mesh, material).at(x, y, z).custom(0, r, g, b, 1f).submit();
```

### Passes

`getPassRenderState(CgRenderPassVariant)` returns the `CgRenderState` for the given pass, compiling nothing.

`hasDepthPass()` — true when an explicit authored `[Depth]` pass is present in the last successful parse
(`hasParsedPass("Depth")`). Parse-state only — does NOT include auto-generated depth variants. `hasCompiledDepthPass()` —
true when a compiled depth program exists (under key `"Depth"`): includes both explicit and auto-generated depth
variants produced by `recompile()`. The query a depth prepass asks before `pipeline(DEPTH, kind)`. Always false for
transparent materials (depth auto-gen is opaque-only).

### Materials and instances

A `CgMaterial` is never changed once made. Values of your own are an instance, made from it:

```java
CgMaterial glow = CgMaterial.load("mymod:shaders/glow.shader");     // shared: draw it as it is
CgMaterialInstance hot = glow.instance()                             // yours: its programs, values of its own
        .set1f("_Strength", 2f).colorARGB("_Tint", 0xFFFF8020);
hot.set1f("_Strength", 3f);                                          // when it changes
world.draw(mesh, hot).submit();                                      // a draw takes either (CgMaterialInterface)
hot.delete();                                                        // once nothing draws it
```

- Values one method writes into several targets take a `CgMaterialValues`: an instance, or a kernel's
  `dispatch.values()` (`CgWorldLights`' grid values reach both).
- Values are stored on the instance's properties and kept until overwritten; the next capture repacks its block only
  if a value moved, so writing the same value again costs a comparison. Unknown names are ignored.
- **Safe before any compile, and compiles nothing**: values are built from the shader's *parse*. Only a source that
  does not parse holds the write by name, the last of each, written in at its next parse.
- A material's own values (its definition's constants and textures) are written as the registry makes it, through
  `CgMaterialProxy.writer()`. An instance or a keyword variant copies them when it is made; nothing a consumer holds
  writes a material.

### Shader Keywords / Variants

Shaders declare optional compile-time feature flags using `#pragma cg_feature` in their preamble.
Each enabled keyword injects `#define NAME 1` into both vertex and fragment sources.
Every unique keyword-set combination is compiled into a separate program and cached lazily, at the
first draw of a pipeline with that combination.

**Declaring keywords in a `.shader` file:**
```glsl
#type spatial

#pragma cg_feature FOG_ON
#pragma cg_feature NORMAL_MAP
```

**Complete example with keyword-gated code:**
```glsl
#type spatial

#pragma cg_feature FOG_ON

Properties {
    _MainTex   ("Main Texture",  sampler2D)  = "white"
    _FogColor  ("Fog Color",     vec4)       = (0.7, 0.7, 0.7, 1.0)
    _FogDensity("Fog Density",   Range(0,1)) = 0.05
}

struct v2f {
    vec2 uv;
    vec3 worldPos;
};

void vertex(out v2f o) {
    gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);
    o.uv        = cg_TexCoord0;
    o.worldPos  = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void fragment(in v2f i, out vec4 fragColor) {
    fragColor = texture(_MainTex, i.uv);

#ifdef FOG_ON
    float dist    = length(i.worldPos);
    float fogFact = exp(-_FogDensity * dist);
    fragColor.rgb = mix(_FogColor.rgb, fragColor.rgb, clamp(fogFact, 0.0, 1.0));
#endif
}
```

**A keyword makes another material:**
```java
CgMaterial fogged = material.keyword("FOG_ON", true);        // made once, then the same object; material unchanged
CgMaterial plain = fogged.keyword("FOG_ON", false);          // back to material itself
boolean on = fogged.isKeywordEnabled("FOG_ON");              // true
CgMaterialInstance mine = fogged.instance();                 // values of your own on that variant
```

A drawer whose keywords follow its values per draw keeps an instance per variant with `CgMaterialInstances`:
```java
CgMaterialInstances panes = new CgMaterialInstances(CgMaterial.load("mymod:shaders/pane.shader"));
CgMaterialInstance pane = panes.of(panes.material().keyword("WITH_NOISE", noise > 0f));   // write every value after
```

**Rules and limits:**
- Maximum **31 keywords** per shader (a game archetype carries its feature mask and its material's static switches); past 8 its variants sit in a sparse table. Exceeding this throws `CgShaderParseException` at parse time.
- Keyword names must match `[A-Z][A-Z0-9_]*` — all uppercase, start with a letter.
- Only names declared with `#pragma cg_feature` in that shader are accepted — `keyword(name, true)`
  throws `IllegalArgumentException` for an undeclared name; `keyword(name, false)` of one not on answers the material.
- `#pragma cg_feature` lines are **never emitted** in the generated GLSL — they are stripped
  during parsing. Only the `#define NAME 1` lines (for enabled keywords) appear in compiled output.
- `keyword()` is safe to call before any compile: it parses, never compiles. A variant starts from its material's values.

### CgMaterial.load(resourcePath) / load(CgMaterialKey)
```java
CgMaterial material = CgMaterial.load("mymod:shaders/terrain.shader");
// or:
CgMaterial material = CgMaterial.load(CgMaterialKey.of("mymod:shaders/terrain.shader"));
```
Both delegate to `CgMaterialRegistry.get().getOrCreate(...)`, the same material on repeated calls. The shader is
parsed at its first ask and compiled per pipeline at its first draw (or `precache()`); a source that does not parse
logs the error, and the material draws nothing until it does.

### CgMaterial.precache() — compiling without stalling a frame

A pipeline's first draw compiles its program on the spot, which is tens of milliseconds a program. A caller that
swaps materials while drawing every frame (the shader graph's previews) polls instead, and keeps drawing the
old material until the new one is ready:

```java
CgMaterial next = CgMaterial.fromSource(source);   // shared by content: the same source answers the same material
// each frame:
if (next.precache()) current = next;                // compiled, or failed: next.lastCompileError() says which
world.draw(mesh, current).submit();                 // the old picture until the new one is ready
```

- `precache()` submits every program of the material (`CgMaterialShader.submitRecompile`) and answers false
  on that frame, always; after that it polls whether the compile has landed.
- A draw before it answers true waits for the compile there, exactly as an unprecached material does.
- It answers false without submitting while `CgWarmBudget` is spent for the frame, so many materials precached at
  once spread over frames.
- It compiles the Forward pass only. The auto-generated depth program is built, synchronously,
  the first time a Depth pipeline or `hasCompiledDepthPass()` asks — a preview never does, and they
  were half of what an edit compiled.
- `-Dcrystalgraphics.shader.parallelCompile=false` makes it answer true at once: the escape hatch.

### Recording — `pipeline(kind)` and `bindings()`

What the engine's recorders ask of a material, through its internal proxy (`material.CgMaterialProxy`, Unreal's
`FMaterialRenderProxy`; a consumer draws through `world.draw` or `pass.draw` and never asks). **Neither touches the
device**, so a recorder may run on any thread:

```java
CgPipeline p = CgMaterialProxy.of(material).pipeline(CgInstanceKind.QUAD);   // Forward pass, keywords enabled now; a CPU key
CgMaterialBindings b = CgMaterialProxy.of(material).bindings();               // its version: the same object until something moves
CgPipeline glow = CgMaterialProxy.of(material).pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);   // ask hasEmissivePass() first
```

- Forward and Emissive pipelines take the enabled keywords and are cached per kind and keyword set, so asking every
  frame allocates nothing; any other pass is built on each call, with no keywords.

- The program behind `p` is compiled on the render thread when a batch first draws with it, or polled with
  `p.precache()`.
- `bindings` puts a sampler's texture at its **index among the declared samplers**, the unit every program is
  wired with at compile — whatever unit `sampler(name, unit, texture)` was given.
- A version is immutable: a property changed after a draw names it makes the next version, and the draw keeps reading
  its own. Writing a value it already holds makes none.
- A buffer the shader's `Buffers { }` declares is bound on an instance with `instance.buffer(name, buffer)` and held
  the same way; `bindings()` with one unbound throws (SHADERS.md § *Reading a kernel's buffers*).

### Hot reload

`CgMaterialRegistry.reloadAll()` (F3+T, a resource pack reload) marks each material's shader asset dirty. The next use
re-reads, re-parses and recompiles it; every material, variant and instance made from it rebuilds its values from the
new parse, keeping each value whose property kept its name and type. On a failed compile the old program stays and the
error is logged. A generated shader (`fromSource`) has no file and is never reloaded: new source is a new material.

### CgVertexFormat.SPATIAL
```java
// Locations: 0 cg_Position (vec3), 1 cg_Normal (vec3), 2 cg_Color (vec4), 3-6 cg_TexCoord0-3 (vec2)
// Stride: 40 bytes, packed (CgVertexFormat.SPATIAL's javadoc)
CgVertexFormat format = CgVertexFormat.SPATIAL;
CgMesh mesh = CgMeshShapes.cube(format);
```

## The sampler budget

A material's samplers are its set's textures from `CgBindingModel.FIRST_TEXTURE`, by declaration order; the
view's (lightmap, depth, scene colour) are another set's and never collide. A shader declaring more than
`CgBindingModel.MATERIAL_TEXTURES` fails to load with a `CgShaderParseException` naming the count.

## Ownership Rules

- `CgMaterialRegistry` owns all `CgMaterial` instances it creates. Call `CgMaterialRegistry.get().deleteAll()` to free them.
- `CgEngineLifecycle.shutdown()` calls `CgMaterialRegistry.get().deleteAll()` automatically.
- Callers that hold a reference to a material must not call `delete()` on it directly — the registry owns teardown.

## Buffers a material reads

A material reads what its source declares: `Buffers { }`, bound by name with `material.buffer(name, buffer)`, and the
engine tokens it names with `#pragma cg_use <token>` (`CgEngineBufferRegistry`; `text` is `CgTextMaterials`'
`TextData`, a uniform block). Nothing is injected into a shader at runtime, so a program's reads are fixed by its
source (rhi SH6, Unreal's shader parameters). A renderer giving each of its materials its own contents for an engine
uniform block uses `overrideBlock(block, contents)`, which a capture keeps by value.

The engine's own blocks (`CgViewBlock`, `CgEnvironmentBlock`, `CgObjectDataBuffer`) are declared in `cg_env.glsl` at
their bindings: never declare them again.

## Guardrails (NOT in this package)

- No render-state management (blend, depth, cull)
- No render-pass system (depth/shadow passes are out of scope)

## Related Packages

| Package | Relationship |
|---------|-------------|
| `material/` | Provides `CgShaderParser` (parse) and `CgMaterialShaderCompiler` (generate GLSL) |
| `gpu/resource/` | `CgUniformBuffer` (UBO frame data), `CgShaderBuffer` (SSBO per-object data) |
| `api/shader/` | `CgShaderPreprocessor`, the source pipeline every shader goes through |
| `material/` | `CgShader`, what each pass and keyword set compiles into |
| `api/vertex/` | `CgVertexFormat.SPATIAL` — canonical spatial format for this pipeline; also `CgVertexSemantic`, `CgAttribType` |
| `render/world/`, `render/graph/` | `CgWorldRenderer` and the graph draw a `CgMesh` under a material, through the mesh store |

---

Also loaded with this folder:

@../../../../../../../../docs/SHADERS.md
