# CgTextRenderer → CgMaterial Overhaul Plan

**Status:** Phase 1 (batch-ownership migration) complete. Phase 2 (CgMaterial/text.shader
consolidation) not started.
**Scope:** `core/src/main/java/com/crystalgraphics/text/render/CgTextRenderer.java` and its
shader backing (`assets/crystalgraphics/shader/{bitmap,msdf,mtsdf}_text.{vert,frag}`).

---

## 1. Motivation

`CgTextRenderer` renders through three hand-maintained raw `CgShader` instances
(`BITMAP_SHADER`/`MSDF_SHADER`/`MTSDF_SHADER`) and three `CgRenderState` constants that are
byte-for-byte identical (Alpha blend, Cull NONE, no depth). The `.frag` files differ only in how
`alpha`/`opacity` is computed from the atlas sample. Goal: collapse these into one `.shader`
CrystalShader material with keyword-selected atlas modes.

---

## 2. DONE — Phase 1: CgBatchRenderer ownership migration

`CgTextRenderer` now owns its full batch lifecycle directly; no `CgMaterial`/keywords/UBO work
was in scope for this phase — it still targets the existing raw shaders/render-state constants.

- **Owns its own `CgBatchRenderer`** (`CgVertexFormat.POS2_UV2_COL4UB`, 1024 initial quads) —
  `CgDynamicTextureRenderLayer`/`CgTextLayers` removed from the text draw path entirely (classes
  left alone, unused elsewhere).
- **`beginBatch()`/`endBatch()`** lifecycle (renamed from `beginFrame()`/`endFrame()` post-launch
  — the renderer is frame-agnostic, this is purely a batching-window scope). No projection
  argument — matches `CgAbstractRenderer.begin()`/`end()`.
- **Standalone-tolerant**: `draw()` auto-wraps itself in begin/flush/end when no batch is active;
  records into an already-open batch otherwise. Only double-`beginBatch()` still throws.
- **`transitionTo`/`flushPending`** replace the old layer's `setShader`/`setTexture`/
  `setRenderState` — flush-on-change collapsed to one flush per real transition.
- **`restoreStateWith(Runnable)`** hook runs after every `endBatch()` so a caller sharing the GL
  context (e.g. `CgUiPaintContext`) can cheaply re-bind its own state without a `CgGlState`
  snapshot/restore round-trip.
- All 7 real call sites migrated (`gl-debug-harness`'s `TextContext`/`TextScene2D`/`HUDRenderer`/
  `AtlasDumpScene`/`WorldTextRenderHelper`/`ImageScene`, `core`'s `CgFontDemo`) — each now wraps
  its per-frame draw calls in one `beginBatch()`/`endBatch()` pair instead of per-draw begin/end.
- Vertex format decision: **`POS2_UV2_COL4UB`, not `SPATIAL`**, for both 2D and world text.
  `SPATIAL` has no vertex-color attribute (needed for multi-color batching within one flush) and
  its `cg_Normal` is wasted bandwidth for flat glyph quads. World-text "3D-ness" comes from the
  modelview matrix multiplying a flat local 2D quad, not from genuine 3D vertex data — a `vec2`
  position through a 4×4 matrix is equivalent to a `vec3` position with `z` baked to 0. Escape
  hatch if real per-vertex depth is ever needed: `POS3_UV2_COL4UB`, not `SPATIAL`.
- `cg_env.glsl`'s instancing/camera macros (`CG_MATRIX_MVP`, `CG_OBJECT_TO_WORLD`, etc.) will
  **not** be used by the eventual text material — each draw call carries its own arbitrary
  transform, unrelated to the frame camera / per-instance object SSBO. Transform delivery for
  Phase 2 is an attached `CgUniformBuffer` (see §4).
- Docs updated: `text/render/AGENTS.md`, `package-info.java`, `CrystalGraphics/AGENTS.md`'s Batch
  Render Layer System blurb.

### 2.1 Phase 1.5 — merged `draw()`/`drawWorld()` into one entry point

`drawInternal`/`submitBatchedQuads` already dispatched 2D-vs-world purely off the context
argument's runtime type, making every `drawWorld()` overload byte-for-byte duplicate of `draw()`.
Deleted all 12 `drawWorld()` overloads; `draw(...)` is now the only entry point (pass a
world-mode context to get 3D behavior). One real caller updated (`WorldTextRenderHelper`, 3 call
sites).

Fixed in the same pass: world text's documented depth-test contract ("depth-tested but not
depth-writing") was never actually implemented — all three render-state constants hardcoded
`CgDepthState.NONE` regardless of world/2D. Added `BITMAP_RENDER_STATE_WORLD`/
`MSDF_RENDER_STATE_WORLD`/`MTSDF_RENDER_STATE_WORLD` (`CgDepthState.TEST_ONLY`), selected via
`context.isWorldText()`.

### 2.2 Phase 1.6 — collapsed `CgWorldTextRenderContext` into `CgTextRenderContext`

The subclass split was pure bloat (only real content: `viewportWidth`/`viewportHeight`,
`updateProjectedSize`, a one-line `isWorldText()` override) and hid a live bug —
`updateProjection()` was a no-op self-assignment due to field shadowing. Deleted the subclass;
`CgTextScaleResolver` gained `isWorldText()`/`updateProjectedSize(...)`/`clearProjectedSizeHint()`
with no-op/false defaults, overridden by `PerspectiveScaleResolver`. `CgTextRenderContext` now
has two factories on one class: `orthographic(w,h)` and `world(projection, viewportW,
viewportH)`.

### 2.3 Phase 1.7 — history-map merge + constructor trim

`previousEffectiveTargetPx`/`previousMsdf` (always read/written together per font-key) merged
into one `Map<CgFontKey, RasterHistory>` (package-private record). `CgTextRenderContext`'s three
public constructors trimmed to one private one + the two factories — confirmed via repo-wide
grep that no call site used the raw constructors directly.

Rejected in this pass: hoisting `PerspectiveScaleResolver`'s `projectedSizeHint` up to the base
class (would force an unused hint param onto `OrthographicScaleResolver`); defensive-copying
`getProjection()`'s returned `Matrix4f` (hot path, no real bug observed, doc convention judged
sufficient).

---

## 3. REMAINING — Phase 2: CgMaterial / text.shader consolidation

Not started. Nothing below has any code written yet.

### 3.1 Resolved

All four original open questions are answered — nothing left to check before writing code.

| # | Question | Answer |
|---|---|---|
| 1 | Depth-off grammar | No `DepthTest OFF`/`NONE` token exists in `CgRenderStateParser`/`CgShaderKeywords` — parsing `DepthTest` always sets `depthTestEnabled = true`. **Not needed anyway** — see §3.2, depth is controlled from Java, not from the `.shader` text. |
| 2 | `POS2_UV2_COL4UB` attribute names | `CgGlslEmitter.emitVertexInputs()` emits, in order: `in vec2 a_pos; in vec2 a_uv; in vec4 a_color;` — `a_color` is a normalized ubyte4, already a `[0,1]` float `vec4` in GLSL, no in-shader unpacking. |
| 3 | UBO binding index | New reserved slot in `CgBindingPoints` (`TEXT_DATA_UBO`), following the existing top-of-range pattern used by `MATERIAL_PROPERTIES_UBO`. Confirmed ample headroom (2 of 36+ slots used today). |
| 4 | STD140 padding | Not needed — the UBO ended up holding only `mat4 u_projection` + `mat4 u_modelview` = 128 bytes, already a multiple of 16 (see §3.3, `u_pxRange` moved elsewhere). |

`text.shader` is the confirmed final name (single material file — see §3.2).

### 3.2 Shader consolidation — one material, depth handled in Java

- One material: `assets/crystalgraphics/shaders/text.shader` (plural `shaders/`, matching
  `example.shader` — distinct from the raw shaders' singular `shader/` path).
- `#type pos2_uv2_col4ub`, `Queue = "Overlay"` (or anything ≥ `TRANSPARENT_THRESHOLD` — load
  bearing: keeps `attemptShadowAutoGen`/`attemptDepthAutoGen` bailing out via their `isOpaque`
  check, so text never gets an auto-generated ShadowCaster/Depth pass).
- One shared `RenderState{}` block: `Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA`, `Cull OFF`. The
  `DepthTest`/`DepthWrite` lines are a **placeholder only** (e.g. `DepthTest LEQUAL` /
  `DepthWrite ON`, just to satisfy the grammar) — never actually observed at draw time.
- **Depth is a runtime override in `CgTextRenderer`, not part of the shader.** `RenderState{}`
  is scoped per `Pass`, not per keyword permutation, so the existing world-vs-2D depth split
  (no-depth for UI vs `TEST_ONLY` for world text) can't be baked into one material's
  `RenderState` block. Instead, `CgTextRenderer` brackets each material bind with the existing
  `CgDepthState` constants: `(context.isWorldText() ? CgDepthState.TEST_ONLY :
  CgDepthState.NONE).apply()` right after `material.bind()`, `.clear()` before
  `material.unbind()` — same shape as today's `activeRenderState.apply()/clear()` bracketing
  `batchRenderer.flush()`, just moved to wrap the material bind. This is also why only **one**
  `text.shader` is needed instead of separate UI/world variants.
- Keywords `MSDF_MODE`/`MTSDF_MODE` (bitmap = neither enabled), toggled on the owned `CgMaterial`
  per batch-key transition — replaces shader-instance selection in `submitBatchedQuads`.
- **Keyword-toggle discipline: every transition sets both keywords explicitly, never a bare
  `enableKeyword()` alone.** `MSDF_MODE`/`MTSDF_MODE` are independent toggles, not a 3-way enum,
  and the material is shared static state (see §3.3) — a stale keyword left on by a previous
  transition (this renderer's or another live `CgTextRenderer` instance's) would silently persist
  into the next bind's compiled variant. Concretely: switching to MTSDF sets
  `MTSDF_MODE=on, MSDF_MODE=off`; to MSDF sets `MSDF_MODE=on, MTSDF_MODE=off`; to bitmap sets both
  off.
- Atlas texture and `u_pxRange` both stay on the Properties-block path (`_MainTex` sampler,
  `_PxRange` float), switched per atlas-page/mode transition via
  `applyProperties(b -> { b.sampler(...); b.set1f("_PxRange", ...); })` — cheap, dirty-flag-gated,
  and independent of the UBO below (`CgMaterialProperties` supports scalars/vecN/samplers fine;
  only `mat3`/`mat4` are rejected, which is why the matrices need the UBO instead).
- **The static material must be obtained via `CgMaterial.load("crystalgraphics:shaders/text.shader")`,
  not `CgMaterial.newInstance(...)`.** `load()` is cache-per-path and participates in
  `CgMaterialRegistry`'s `reloadAll()` (F3+T hot-reload) and `deleteAll()` (canonical GL teardown)
  automatically; `newInstance()` deliberately opts out of both. Using `load()` is what makes the
  shared static material hot-reload and tear down for free, matching every other long-lived
  material in the engine.

### 3.3 Transform delivery: one shared static `CgUniformBuffer`

Supersedes today's `shader.applyBindings(...)` calls (`CgTextRenderer.java:773-778`) for the two
matrices only — `u_pxRange` moved to the Properties block above. Sanctioned attach-a-buffer
pattern per `gl-debug-harness`'s `CgAttachedBufferStressScene` (`terrain_ubo.shader`); everything
else here is CgTextRenderer-specific reasoning about *how often* to actually rewrite it.

- **One `CgUniformBuffer`** (block `"TextData"`, STD140): `mat4 u_projection`, `mat4
  u_modelview` — 128 bytes, no padding needed.
- **Owned as a `static` field on `CgTextRenderer`**, not per-instance — mirrors the class's
  already-static `BITMAP_SHADER`/`MSDF_SHADER`/`MTSDF_SHADER`. The engine is single-GL-thread with
  no interleaved draws, so one shared scratch UBO rewritten immediately before each renderer's own
  draw is safe and avoids N idle GPU buffers for N live `CgTextRenderer` instances.
  `material.attach(textTransformUbo)` once, at class-init.
- **Created via `CgShaderBufferRegistry.get().getOrCreateUbo(...)`, not a bare
  `CgUniformBuffer.create(...)`.** A directly-`create()`d buffer bypasses the registry entirely and
  nothing frees it at `CgGraphicsLifecycle.destroyContext()`. That's tolerated today for the raw
  static shader fields (which have the same property), but a buffer *shared across every live
  `CgTextRenderer` instance* needs an owner other than any one instance's `delete()` — no
  individual renderer should ever free a resource other still-live renderers depend on. Registering
  it puts it under the registry's `deleteAll()`, already wired into the canonical teardown sequence
  (step 7) alongside every other shared GPU resource.
- **Rewritten/uploaded unconditionally once per `draw()` call, no dirty-check/cache.** Rewrite
  happens exactly once per `submitBatchedQuads()` invocation (up front, before the per-transition
  loop) — never once per batch-key transition — so it's already off the hot path regardless. A
  cross-call "did this change since last time" cache was tried and rejected: it needs `static`
  shadow state (the UBO is a shared singleton — an instance-level cache would let renderer B
  wrongly skip a reupload after renderer A clobbers the shared buffer) plus defensive-copied
  `Matrix4f`s (a caller's `PoseStack` matrix is mutable) — real complexity for a win that only
  applies to two consecutive draws sharing the exact same transform. `CgUniformBuffer`'s write
  model can't skip just one matrix either way (`beginRecord()` always reserves/zeroes the whole
  record), so a genuine partial skip would need a second UBO (its own binding slot + GLSL block)
  for one matrix alone — not worth it next to the per-transition `CgMaterial.bind()` cost already
  accepted below (§3.4).
- Per actual rewrite: write buffer → `endRecord()` → `upload()` → `material.bind()` →
  `textTransformUbo.bind()` → draw → `material.unbind()`.
- Field names keep the `u_` prefix (flat UBO scope, no block prefix in GLSL) so shader-side
  references read the same as today's raw uniforms.

### 3.4 Fast-path / general-path submit

Mixed-tier batches (bitmap+MSDF in one `draw()` call — font fallback, or emoji/color glyphs which
are structurally bitmap-only) are permanent, expected behavior, not an edge case to special-case
away.

- **Fast path** (dominant case — one atlas tier for the whole string): detect distinct-key-count
  == 1, skip sorting, bind material once, submit all quads, unbind once. Detection mechanism:
  build `batchKeys[]` once (already required either way), then one linear scan comparing every
  entry against `batchKeys[0]` — only if that scan finds a mismatch does the existing
  insertion-sort general path run below. No separate counting pass, no upfront sort.
- **General path** (distinct-key-count > 1): keep the existing `CgDrawBatchKey`
  insertion-sort-and-transition loop, driven through `material.bind()`/`applyProperties()`/UBO
  rewrite/`unbind()` per transition instead of raw shader/texture calls.
- Accepted cost: `CgMaterial.doBind()` snapshots 6 GL slots per bind — heavier per-transition than
  today's plain `setTexture()`/`setShader()`. Acceptable since transitions are rare relative to
  per-glyph work, and the fast path (no transitions) is where the actual win is.
- Out of scope, unrelated: today's transient bitmap-fallback-reruns-whole-batch behavior
  (`usedBitmapFallback`) — different mechanism, not being touched.

### 3.5 Implementation steps

1. Add a `CgBindingPoints.TEXT_DATA_UBO` slot per §3.1, and create the UBO via
   `CgShaderBufferRegistry.get().getOrCreateUbo(...)` (not a bare `CgUniformBuffer.create(...)`)
   so it's covered by the registry's `deleteAll()` teardown — see §3.3.
2. Write `assets/crystalgraphics/shaders/text.shader` per §3.2.
3. Load the static material via `CgMaterial.load("crystalgraphics:shaders/text.shader")` (not
   `newInstance`) per §3.2; add the static `CgUniformBuffer` per §3.3 (no last-uploaded cache —
   rewritten unconditionally once per `draw()` call).
4. Replace `transitionTo`/`flushPending`'s raw shader/render-state/texture calls with
   `CgMaterial` bind/keyword/property calls — every transition sets **both**
   `MSDF_MODE`/`MTSDF_MODE` explicitly (§3.2's keyword discipline, never a bare
   `enableKeyword()`) — bracketed by the `CgDepthState.NONE`/`TEST_ONLY` apply/clear from §3.2
   (keyed off `context.isWorldText()`, same as today's `worldText` boolean in
   `submitBatchedQuads`), plus the unconditional transform upload from §3.3 (once per `draw()`
   call, up front).
5. Implement fast/general path split in `submitBatchedQuads` per §3.4 (linear-scan detection, no
   extra sort).
6. Delete `BITMAP_SHADER`/`MSDF_SHADER`/`MTSDF_SHADER` and the six `CgRenderState` constants once
   the new path is verified equivalent.

---

## 4. Explicitly Out of Scope

- Routing text through `CgRenderPipeline`'s instanced object-buffer path — doesn't fit the
  immediate-mode quad-batch model text needs (would require a synthesized model-matrix-per-glyph
  object record).
- Fixing `parsePreambleDirectives()` to forward non-`#` lines at material scope — moot now that
  §3.3 uses an attached UBO instead of hand-rolled preamble uniforms.
- Changing the transient bitmap-fallback behavior (§3.4).
- Texture-array-based atlas paging as an alternative to per-batch-key texture rebinds.
