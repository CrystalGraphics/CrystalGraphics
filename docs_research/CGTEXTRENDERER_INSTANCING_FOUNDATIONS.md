# CgTextRenderer instancing overhaul

## Status: done

`CgQuadRenderer` (`gl/render/CgQuadRenderer.java`) — the general-purpose SSBO/TBO-backed instanced
quad renderer this doc originally designed — is built, and `CgTextRenderer` has been fully migrated
onto it. There is no more owned `CgBatchRenderer`/`TEXT_DATA_UBO`/`uploadTransformIfNeeded` machinery
in `CgTextRenderer` — see "The completed `CgTextRenderer` migration" below for what changed there.

## Background: why this was needed

The long-term goal (stated by the user) is to support many independent **per-label style
properties** — outline color/stroke width, rainbow/gradient text styles, and future additions —
where multiple differently-styled/differently-transformed labels still merge into one GPU draw
call. A shared-uniform model (the original design) or a vertex-attribute-divisor instancing model
(`CgInstanceRenderer`/`CgQuadInstanceRenderer`) both hit a hard **GPU vertex-attribute-slot
ceiling** (~16 slots total) as more per-label properties are added. An **SSBO/TBO-backed,
per-instance model** (indexed by `gl_InstanceID`, arbitrarily extensible struct, no attribute-slot
pressure) is the one that scales to that goal.

`CgQuadInstanceRenderer`/`CgInstanceRenderer`/`CgInstanceFormat` were considered and rejected for
this specific need: real, working instancing infrastructure, but vertex-attribute-divisor based
(fixed GL attribute-slot budget), so it hits the exact ceiling problem an ever-growing set of style
properties needs to avoid. They remain the right tool for small, fixed, never-growing per-instance
field sets (e.g. simple particle sprites) — `CgQuadRenderer` is not a replacement for them, it's a
fourth sibling in `gl.render` for when per-instance data needs to grow without limit.

**Why 3 vectors (`origin`/`right`/`up`), not a `mat4` or 4 baked corners:** transforming
`origin`/`rightVec`/`upVec` through a `PoseStack.Pose` matrix once per quad
(`transformPosition`/`transformDirection`) and reconstructing corners in the shader as
`origin + u·right + v·up` is mathematically equivalent to transforming all 4 corners individually,
for any **affine** transform (translation + rotation/scale/shear — everything a `PoseStack` pose
actually is). It's cheaper (3 transforms instead of 4) and leaner (9 floats instead of a 16-float
`mat4`, since only origin/right/up ever differ from a flat quad). This is exactly what
`CgQuadRenderer.Quad.pose(Matrix4f)` implements — see its javadoc and `submit()` body for the real
code. The one documented limitation: this assumes an affine transform; a genuinely projective
(`w`-affecting) `PoseStack` pose would not reconstruct correctly this way. In practice `PoseStack`
poses are always affine, so this is a stated assumption, not an active limitation.

For the full class design (fixed six-field instance schema, `Quad` fluent builder, `useMaterial`'s
auto-attach/auto-flush/bind-every-call contract, the `CG_QUAD_WORLD_POS`/`CG_QUAD_UV`/
`CG_QUAD_COLOR`/`CG_QUAD_NORMAL` macros in `cg_env.glsl`, the reserved `CgBindingPoints.QUAD_RENDERER`
binding, `CgShaderBuffer.uploadRaw(...)`), read `CgQuadRenderer.java` directly — its javadoc is the
living source of truth, not this doc.

---

## The completed `CgTextRenderer` migration

`CgTextRenderer` used to implement the same accumulate-then-instanced-draw idea itself, by hand:
an owned `CgBatchRenderer` for CPU vertex staging (4 absolute vertex positions written per glyph in
`addQuadFromPlacement`), and a private `TEXT_DATA_UBO` (`u_Projection` + `u_ModelView`, STD140)
uploaded as shared, whole-draw-call GPU state — forcing a flush on every transform change via
`uploadTransformIfNeeded`/`activeProjection`/`activeModelView`. That whole apparatus is now deleted;
`CgTextRenderer` holds one `CgQuadRenderer` instance instead and got measurably leaner:

- **`modelView` → fully per-instance**, via `quadRenderer.quad().at(qx,qy).size(w,h)
  .uv(u0,v0,u1,v1).color(argb).pose(modelView).submit()` in `addQuadFromPlacement` — one call
  replaces the old 4 `vertex()...endVertex()` calls. No shared UBO, no flush needed on a
  modelView/transform change — different-pose glyphs already merge into one draw call.
- **`projection` → reasserted into the engine's shared per-frame `CgFrameData`**
  (`CgRenderPipeline.getInstance().getFrameData()` + `prepareFrame()`, `viewMatrix` set to
  `identity()`), inside `flushPendingMaterial()` — the same pattern every other CrystalShader
  consumer (`CgUiPaintContext`, `CgQuadRendererTestScene`) already uses for `cg_ProjMatrix`.
- **One remaining, much smaller correctness guard**: a single `Matrix4f activeProjection` field
  (`activeModelView` is gone entirely). `submitBatchedQuads` flushes first if `context.getProjection()`
  differs from `activeProjection` before queuing more glyphs — necessary because projection, unlike
  modelView, is still shared GPU state at flush time, so a mid-batch `context(...)` switch could
  otherwise mis-project glyphs queued under the previous context.
- **Color convention conversion, once, at the call site**: `Draw.color(int rgba)` is
  `0xRRGGBBAA` (unchanged public contract, matches `CgVertexConsumer.colorRgba`); `Quad.color(int
  argb)` is `0xAARRGGBB` (alpha in the top byte). `addQuadFromPlacement` converts with
  `(rgba >>> 8) | ((rgba & 0xFF) << 24)` right before calling `Quad.color(...)`.
- **`text.shader`'s `vertex()`** no longer declares/reads `u_Projection`/`u_ModelView` from an
  attached UBO — it uses `cg_ProjMatrix` (the ordinary frame block) plus
  `CG_QUAD_WORLD_POS`/`CG_QUAD_UV`/`CG_QUAD_COLOR` directly:
  ```glsl
  void vertex(out v2f o) {
      gl_Position = cg_ProjMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
      o.uv    = CG_QUAD_UV;
      o.color = CG_QUAD_COLOR;
  }
  ```
- **`CgBindingPoints.TEXT_DATA_UBO`** (the reserved UBO slot) is deleted along with the buffer it
  backed — `FRAME_DATA_UBO`/`MATERIAL_PROPERTIES_UBO` are the only two engine-reserved UBO slots now.
- **`CgBatchRenderer` itself is untouched** — it's still used elsewhere in the engine (UI batching,
  `CgRenderLayer`, etc.); `CgTextRenderer` simply no longer depends on it.

Everything upstream of quad placement (shaping, kerning, atlas placement, raster-tier scale
normalization) and `CgDrawBatchKey`-driven atlas-mode/texture-page batch breaking are unaffected —
those are orthogonal to the transform/instancing model this migration replaced.

---

## Remaining decisions (still open — not part of the completed migration)

### Atlas texture array — investigated, not started

To let one instanced draw call span multiple atlas pages (removing the last real batch-break
dimension besides atlas *mode*), glyph atlases would move from one independent `GL_TEXTURE_2D` per
page to one `GL_TEXTURE_2D_ARRAY` per atlas family, with each page becoming a layer index instead of
a separate texture. Investigation below covers what that actually touches. **Not resolved, not
started — this is a design/scoping pass, not an implementation.**

#### Why this pays off (what today's batching actually costs)

`CgTextRenderer.submitBatchedQuads`'s packed sort key (`CgTextRenderer.java`, `packSortKey`) currently
breaks batches on `(mode, textureId, pxRange)`. `textureId` is page-specific — every `CgGlyphAtlasPage`
owns its own raw GL texture id (`CgGlyphAtlasPage.java:70`), so text spanning N pages of the same
atlas family costs N draw calls even though every one of those glyphs is otherwise mode/format
identical. Collapsing all pages of one family into one array's layers turns `textureId` from an
unbounded per-page dimension into a fixed 2-valued one (bitmap array vs. distance-field array) — the
actual number of draw calls a multi-page HUD/paragraph needs would stop scaling with page count.

#### 1. Storage model: `CgTexture2DArray` needs new capability, not just reuse

`gl/texture/CgTexture2DArray.java` today is built for the "N whole images, one array, set once, maybe
`reload()` from disk" case — `create(paths...)`/`upload(CgImageData[])` always does a full
`glTexImage3D` (reallocating every layer) followed by `glTexSubImage3D` for *all* layers at once. There
is no "allocate an empty N-layer array up front, then push a sub-rectangle into one specific layer
later" entry point — which is exactly the atlas use case (`CgGlyphAtlasPage.uploadBitmap`/`uploadMsdf`
today call `glTexSubImage2D` into one page's texture per completed glyph). This is new code:
an `allocateEmpty(width, height, layers, spec)` factory plus an `uploadLayerRegion(layer, x, y, w, h,
data)` method that calls `glTexSubImage3D(target, 0, x, y, layer, w, h, 1, format, type, buffer)` —
`CgGL` already exposes `glTexImage3D`/`glTexSubImage3D` (used internally by `CgTexture2DArray` already),
so the low-level GL plumbing exists; only the atlas-shaped entry points on top of it don't.

#### 2. Fixed layer count vs. growth

A `GL_TEXTURE_2D_ARRAY`'s depth is fixed at `glTexImage3D` time — unlike today's page list
(`CgGlyphAtlas.pages`, `ArrayList<CgGlyphAtlasPage>`, which just appends a brand-new independent
texture when full), you cannot add a layer to an existing array without reallocating the whole array
and copying every existing layer into the bigger one (`glCopyImageSubData`, GL 4.3+, or an FBO-blit
fallback for the 3.x baseline). Two options, not mutually exclusive:

- **Preallocate a generous fixed layer count** per atlas family (the GL 3.0+ spec guarantees
  `GL_MAX_ARRAY_TEXTURE_LAYERS >= 256`, so headroom exists) and treat hitting the cap as exceptional.
- **Grow-by-reallocation** as a fallback when the cap is hit — real code, real cost (a full copy of
  every existing layer), and something you want to hit rarely, not on a steady-state basis.

This makes **DIAGNOSIS A3 (paged atlas has no eviction/budget policy — `tickFrame` is a no-op)
a hard prerequisite, not a nice-to-have**: without some bound on live glyph/page count, the array
either has to be sized speculatively large (wasting VRAM up front) or will hit its layer cap and pay
the expensive reallocation path on a codepath the current design assumed would just cheaply allocate
one more independent texture. Eviction/page-budget work should land *before or alongside* this, not
after.

#### 3. Format unification across bitmap/MSDF/MTSDF

An array's layers must all share one GL internal format. Today bitmap (`GL_R8`) and distance-field
(`GL_RGB16F` for MSDF, `GL_RGBA16F` for MTSDF) already live in separate atlas families
(`pagedBitmapAtlases` vs. `pagedMsdfAtlases` in `CgFontRegistry`), so they naturally become two
separate arrays — no forced unification needed there. But MSDF and MTSDF today use *different*
formats (`GL_RGB16F` vs `GL_RGBA16F`) despite already sharing one shader keyword and one code path
(`text.shader`'s own comment: "one keyword covers both distance-field atlas types... the fragment
logic is byte-for-byte identical for both today"). Note also that `GL_RGB16F` isn't even in
`CgTextureType` today (only `RGBA16F` is) — the atlas classes hand-roll that constant themselves
(itself one of DIAGNOSIS A2's findings). Recommendation: standardize the distance-field array on
`RGBA16F` for both MSDF and MTSDF (MSDF glyphs simply leave the alpha channel unused), which both
avoids adding a 3-component array format with its own driver-alignment history and lets one array
serve both. Needs confirming that unused-alpha MSDF layers cost nothing beyond the extra channel's
storage (they don't — no bandwidth cost for a `discard`ed/unread channel at sample time).

#### 4. Placement, instance data, and shader changes

- `CgGlyphPlacement` already carries `pageIndex` (`CgGlyphPlacement.java:61-62`) — under the array
  model this *is* the layer index, so no new field is needed there, just a rename/repurpose of what
  `pageIndex` means once pages stop being separate textures.
- `CgQuadRenderer.INSTANCE_FORMAT` (`CgQuadRenderer.java:100-105`) needs a new per-instance field —
  e.g. `float atlasLayer` — added the way the format's own doc mandates ("named, typed fields per
  concrete feature, added to `INSTANCE_FORMAT` when each feature is actually built," per this doc's
  own "Per-label style property schema" decision above). `cg_env.glsl`'s `CG_QUAD_UV`/`CG_QUAD_COLOR`-style
  zero-arg macros need a sibling (`CG_QUAD_ATLAS_LAYER`) bridging it into the fragment stage as a `flat`
  varying (matching how `CG_INSTANCE_ID` is already bridged as `flat in int cg_InstanceId` — layer
  index must not be interpolated across a quad's two triangles).
- `text.shader`'s `_MainTex` property changes from `sampler2D` to `sampler2DArray`. This is **already
  a supported property type** — `CgMaterialProperty.Type.SAMPLER2D_ARRAY` exists
  (`CgMaterialProperty.java:51`) and `CgPropertiesParser`'s type whitelist already includes
  `sampler2DArray` — but nothing in the codebase actually uses it yet, so this would be the first real
  consumer exercising that path. `fragment()`'s `texture(_MainTex, i.uv)` calls become
  `texture(_MainTex, vec3(i.uv, i.layer))`.
- Sampler *binding* needs no material-system changes: `CgMaterialProperty.bindSamplerTexture()`
  already dispatches generically through `CgTexture.bind(unit)` (`CgMaterialProperty.java:330-335`),
  which resolves to whatever `getTarget()` returns — so a `CgTextureMutable`-style non-owning wrapper
  (or a real `CgTexture2DArray`) over `GL_TEXTURE_2D_ARRAY` (`0x8C1A`) slots in without touching
  `CgMaterialProperties`/`CgMaterial` at all. This is a case where the material layer's existing
  genericity already carries the new requirement for free.

#### 5. Batching payoff, and an adjacent win it exposes

Once `textureId` collapses from "which page" to "which array" in the sort key, the next-biggest
remaining batch-break dimension is `pxRange` — currently a per-*batch* `CgMaterial` property
(`transitionToMaterial`'s `b.set1f("_PxRange", pxRange)`), forcing a flush on every pxRange change
even within a single array/page. Since this work already touches `CgQuadRenderer.INSTANCE_FORMAT` and
`CgGlyphPlacement`, promoting `pxRange` to a per-instance field too (same mechanism as `atlasLayer`)
would let a single draw call span different pxRange configs as well — collapsing the sort key down to
just `mode` (bitmap vs. distance-field, 2 values). **Optional, adjacent scope** — worth deciding
explicitly rather than doing by default, since it's not required to get the page-batching win.

#### 6. `CgGlyphAtlasPage`/`CgGlyphAtlas` ownership inversion

Today `CgGlyphAtlasPage` owns one GL texture (`CgGlyphAtlasPage.java:70`, `textureId`) plus one
`CgPackingStrategy`. Under the array model, ownership inverts: the array texture is owned once, by
whatever replaces `CgGlyphAtlas` (or a new class), and a "page" becomes a lightweight
`(layerIndex, CgPackingStrategy)` pair with no GL resource of its own. **The packing algorithm layer
(`text/atlas/packing/` — `MaxRectsPacker`, `CgGuillotinePacker`) needs zero changes** — per its own
`AGENTS.md`, it's already correctly ignorant of GL/texture concerns and only fits rectangles into a
page-sized bin; a bin is a bin whether it's backed by an independent texture or an array layer. Only
the upload/texture-creation code in `CgGlyphAtlasPage` and the page-creation code in
`CgGlyphAtlas` change. The existing `createForTest`/`skipGlUpload` pattern
(`CgGlyphAtlasPage.java:157-162`, `CgGlyphAtlas.java:138-152`) needs an equivalent no-GL path
preserved so packing/placement unit tests keep working without a real GL context.

This also creates real pressure to finally resolve **DIAGNOSIS A1/A2** (the legacy single-page
`CgGlyphAtlas`/effective-size paths in `CgFontRegistry`): the legacy evicting atlas has no page/layer
concept at all — it is fundamentally incompatible with an array-backed model without its own separate
rewrite. Migrating the paged path to arrays while the legacy path still exists means maintaining two
increasingly divergent storage models instead of one; retiring the legacy path first (or alongside)
removes that duplication before it gets worse.

#### 7. Harness impact

`gl-debug-harness`'s atlas-dump scenes (`AtlasDumpScene`, `TextScene2D`) currently screenshot each
populated page directly, since each page is its own standalone 2D texture
(`CgFontRegistry.findAllPopulatedPagedBitmapPages`/`findAllPopulatedPagedMsdfPages` enumerate
`CgGlyphAtlasPage`s for exactly this purpose). Dumping one layer out of an array texture needs a new
capture path — you can't screenshot an array layer directly the way you can a standalone texture; it
needs a blit (`glCopyImageSubData` or an FBO render-to-layer step) into a temporary 2D texture first.
Real, additional harness-side work, not just a core-side change.

#### 8. Open verification item: LWJGL2/mc1710 path

`GL_TEXTURE_2D_ARRAY` is core since GL 3.0, matching this project's stated GL 3.x+ baseline, and
`CgTexture2DArray` already lives in loader-blind `core/`, dispatching through `CgGL`/the platform SPI
like everything else — so this should work unmodified on `mc1710`'s `Lwjgl2GlDispatch` the same as
`mc1201`'s backend. Not expected to be a problem, but worth an explicit smoke-test given this
codebase's own documented history of GL-family fragmentation (Core/ARB/EXT waterfalls exist
specifically because "one conformant driver" assumptions have broken before here).

#### Summary: what this actually requires, in order

1. (Prerequisite) An eviction/page-budget policy for the paged atlas (DIAGNOSIS #2) — without it,
   layer growth either over-allocates VRAM or hits the expensive reallocate-and-copy path routinely.
2. New `CgTexture2DArray` capability: allocate-empty-with-N-layers + per-layer sub-rect upload.
3. Format decision: unify MSDF/MTSDF onto `RGBA16F` (one array for both); bitmap array stays `R8`.
4. `CgGlyphPlacement.pageIndex` reinterpreted as array layer index (no new field); new `atlasLayer`
   per-instance field on `CgQuadRenderer`/`cg_env.glsl`/`text.shader`; `_MainTex` becomes
   `sampler2DArray` (already-supported property type, first real consumer).
5. `CgGlyphAtlasPage`/`CgGlyphAtlas` ownership inversion (array owns the texture; pages become
   layer indices) — packing algorithms (`text/atlas/packing/`) untouched.
6. Sort-key simplification in `CgTextRenderer.submitBatchedQuads` (`textureId` → small fixed
   array-id space); optional adjacent win: promote `pxRange` to per-instance data too.
7. Retire the legacy single-page `CgGlyphAtlas` path (DIAGNOSIS #1) — it has no layer concept and
   would otherwise become a second, permanently-diverging storage model.
8. New harness capture path for dumping individual array layers.

### Per-label style property schema (outline, stroke, rainbow, future) — decided: named fields

**Decided: named, typed fields per concrete feature, added to `CgQuadRenderer.INSTANCE_FORMAT`
when each feature is actually built — not generic reserved slots.** E.g. when outline is actually
implemented, `strokeColor` (vec4) and `strokeWidth` (float) get added as real named fields, not
written into a pre-reserved generic `custom1`/`custom2`. Self-documenting on both the Java and GLSL
side, matching how `CgBufferFormat` is already used everywhere else in this engine. Still open:
whether fragment-stage effects need new `.shader` keywords (a compile-time variant — another
batch-break dimension) or can stay fully data-driven (`if (strokeWidth > 0)`, no new keyword) —
data-driven is likely preferable given the whole point of this migration was fewer batch breaks,
but each feature should confirm this when it's actually built.

### `CgUiPaintContext`/`CgUiRenderer` migration onto `CgQuadRenderer` — deferred

Named as the intended second consumer of `CgQuadRenderer` (CrystalGUI,
`core/src/main/java/com/crystalgui/render/`). `CgUiRenderer.submitQuad(x, y, w, h, u0, v0, u1, v1,
argb)` already has close to the target shape — swapping its internal `CgBatchRenderer` for a
`CgQuadRenderer` is a body-only change, with its PoseStack-aware `VertexWriter` baking
`origin`/`right`/`up` once per quad the same way `CgTextRenderer.addQuadFromPlacement` now does.
**Not scoped into any implementation pass yet** — a separate, later step in CrystalGUI, not bundled
with the text migration above.
