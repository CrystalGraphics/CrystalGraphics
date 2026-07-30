# GL State Capture — Sync-Stall Findings (for a future `CgGlScope` / `GLStateMirror` rewrite)

**Status:** findings only, no fix applied. Recorded 2026-07-28 during a text-rendering profiling session.
**Design resolved 2026-07-30** → [`CGGLSTATEMANAGER_PLAN.md`](CGGLSTATEMANAGER_PLAN.md). This document
remains the *measurement* record; the plan supersedes its "Directions for the rewrite" section.
**Scope:** `CgGlState` / `CgGlScope` / `GLStateMirror`, and the wider "cheap call that is actually a
sync point" pattern found alongside it.

---

## TL;DR

`CgMaterial.doBind()` calls `CgGlState.save(BLEND, DEPTH, CULL, STENCIL, ALPHA_TEST, COLOR_MASK)`
on **every material bind**. None of those six slots are covered by `GLStateMirror` (it only mirrors
FBO and PROGRAM), so all six capture via unconditional `glGet*` — roughly **25–30 `glGet` calls per
bind**.

Every `glGet` is a driver synchronisation point: the GPU must drain queued work before it can
answer. So the cost is set by *pipeline depth*, not by anything the method does.

**Measured: 1,599 ms across 838 frames (~1.9 ms/frame average, spiking to 39 ms)** in a scene
issuing only two text draws per frame. This is engine-wide — every `CgMaterial.bind()` pays it, in
Minecraft as well as the harness.

---

## Measurement

Captured with `CgProfiler` (`com.crystalgraphics.util.profiling`), harness scene `text-3d`,
1781-glyph CJK layout, 838 frames / 8 s window, NVIDIA RTX 4070 SUPER, GL 4.6.

`doBind` broken into its six operations, totals across the run:

| phase | total |
|---|---|
| **`stateSave`** (`CgGlState.save`) | **1599.12 ms** |
| `renderState` (`.apply()`) | 22.28 ms |
| `propsUpload` (material props UBO) | 21.38 ms |
| `samplers` | 10.83 ms |
| `shaderBind` (`glUseProgram`) | 4.77 ms |
| `uboBind` (`glBindBufferBase`) | 2.11 ms |
| `wire` | 1.04 ms |

`stateSave` is ~97% of all `doBind` time. Per slow frame it is essentially the entire cost:

| frame | `doBind` total | `stateSave` | share |
|---|---|---|---|
| fr=136 | 39.27 ms | 38.74 ms | 98.6% |
| fr=110 | 33.36 ms | 33.18 ms | 99.5% |
| fr=122 | 16.21 ms | 15.99 ms | 98.6% |
| fr=53  |  4.34 ms |  4.10 ms | 94.5% |
| fr=353 (normal) | 0.54 ms | ~0.5 ms | — |

The 0.5 ms → 39 ms swing with no change in what is being captured is the signature of a
synchronisation stall rather than per-call overhead.

---

## Why the mirror does not help

`CgGlState.save(CgGlSlot...)`:

```java
boolean useGlGet = forceGlGet || gapOnlyMode || !mirrorOkFbo || !mirrorOkPrg;
for (CgGlSlot slot : slots) states[slot.ordinal()] = capture(slot, useGlGet);
```

with the load-bearing comment:

```java
// Resolve mirror-vs-glGet trust ONCE before any capture.
// Passed only to FboState and ProgramState — the two slots that can use the mirror.
```

`useGlGet` is threaded only into `FboState.capture` and `ProgramState.capture`. Every other slot
ignores it. `BlendState.capture()` for example is unconditional:

```java
boolean enabled  = CgGL.glGetBoolean(CgGL.GL_BLEND);
int srcRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_SRC_RGB);
int dstRgb   = CgGL.glGetInteger(CgGL.GL_BLEND_DST_RGB);
int srcAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_SRC_ALPHA);
int dstAlpha = CgGL.glGetInteger(CgGL.GL_BLEND_DST_ALPHA);
// + 2 more for blend equations
```

**Consequence:** this is *not* a harness artifact. An earlier hypothesis in the session was that the
stall came from `GLStateMirror` being `UNKNOWN` outside Minecraft (the mirror is fed by the ASM
coremod, which the harness does not run). That is wrong — the mirror would not have been consulted
for these slots even with the coremod active. **The cost is identical in production.**

---

## Directions for the rewrite

Not prescriptions — options with their trade-offs, for whoever picks this up.

1. **Extend the mirror past FBO/PROGRAM.** `GLStateMirror` + `CrystalGLRedirects` already intercept
   GL calls process-wide to keep FBO/PROGRAM truthful; blend/depth/cull/stencil/colormask are the
   same shape of problem. Removes the `glGet`s entirely. Cost: the mirror must be right for *every*
   mutation path including other mods, which is exactly why it is described as old and
   rewrite-worthy.

2. **Do not save what you are about to overwrite.** `doBind` captures blend/depth/cull/etc. and then
   immediately calls `getPassRenderState(variant).apply()`, which overwrites most of them
   unconditionally. Reading state you are guaranteed to replace is pure waste. Narrowing the saved
   slot set to those the material does *not* set is a much smaller change than (1) and needs no
   mirror correctness guarantees.

3. **Save lazily / once per pass rather than per bind.** Many binds happen back-to-back inside one
   pass with no foreign GL code between them. One save/restore around the pass would collapse N
   captures into 1. Needs a notion of pass scope the material layer does not currently have.

4. **Shadow state CPU-side in `CgGlState` itself.** Instead of a process-wide mirror, have the
   engine track only what *it* sets, and treat foreign mutation as a cache invalidation event. Less
   ambitious than (1), and honest about the fact that CrystalGraphics cannot see every GL call
   anyway outside the coremod.

~~Worth pairing with: the fact that `CgRenderPipeline.execute()` uses `CgGlState.saveAll()` (all 16
slots) around each pass — same problem, larger multiplier, not measured here.~~

> **Correction, 2026-07-30.** That last line was wrong. `saveAll()` has **no call sites** —
> `executeOpaquePass` (`:314`) and `executeTransparentPass` (`:344`) each pass an explicit 9- and
> 6-slot list. The pass-level boundary is therefore already narrow *and* already correctly placed;
> the multiplier is the nested per-bind save inside it, not the pass save itself.

---

## Resolved 2026-07-30 — see [`CGGLSTATEMANAGER_PLAN.md`](CGGLSTATEMANAGER_PLAN.md)

The four options above are settled. **(3) + (4) won, combined; (1) is dead.**

**Why (1) is dead, permanently.** Extending the mirror assumes process-wide interception can be made
omniscient. It cannot: our redirector is modelled on Angelica's, targets the same call sites, and in
observed cases **Angelica's redirector was redirecting ours into its own.** Two transformers
competing for the same bytecode cannot both be authoritative. Any third mod doing raw GL ends the
guarantee regardless. The premise fails on the mirror's *best* target, and on the other three the
mirror is never fed at all.

**What replaces it.** A shadow-state manager whose truth enters only at six declared transfer points,
where each platform *pours* its own state manager into ours — `GlStateManager`/`RenderSystem` on
1.20.x, Angelica's shadow on 1.7.10, batched `glGet` only as the degraded fallback. Between those
points the shadow is authoritative by assertion, and `apply` diffs so unchanged state issues no call.

**Also settled: (2) is subsumed.** "Don't save what you're about to overwrite" becomes moot once
nothing is saved per bind at all.

Two further facts found while planning, both of which make this cheaper than this document assumed:

- `CgRenderState.apply()` **does not diff** — it writes unconditionally. So the captured values were
  never consulted for most slots. The reads bought nothing even in principle.
- `CgMaterial.stateScope` is a **single mutable field**, so interleaved or re-entrant binds already
  clobber it: the earlier scope is never closed and its state never restored. The per-bind save was
  not merely wasteful, it was incorrect under `drawChain`.

---

## The wider pattern (three instances, same shape)

All three were justified in comments/javadoc as "cheap" by byte count or call count. All three are
synchronisation points whose real cost is set by how much GPU work is queued behind them. Worth
treating as a review heuristic: **a GL call being small is not evidence that it is cheap.**

| # | Site | Claimed | Measured | Status |
|---|---|---|---|---|
| 1 | `CgTextRenderer.syncProjection` — UBO write per draw | *"Cheap: one small UBO write per `draw()` call, not per glyph"* | 438 ms of `glMapBufferRange` for 146 KB total (~0.19 ms/call × 2,286 calls) | **Fixed** — small writes (≤256 B) now use `glBufferSubData` via `CgStreamBuffer.uploadSmall`; UBO map time → 0.00 ms |
| 2 | `CgTexture2DArray.growLayers` — CPU-mirror replay on atlas growth | growth is "a cold-path operation" | 3.8 ms → 65.7 ms per growth, scaling linearly with atlas size (~0.87 ms/MB); 204 MB re-uploaded per warmup | **Fixed** — GPU-side copy via new `CgTextureCopy`; 65.7 ms → 0.15 ms |
| 3 | `CgGlState.save` in `CgMaterial.doBind` — ~25–30 `glGet`s per bind | implied cheap; mirror assumed to cover it | 1,599 ms / 838 frames, 0.5 ms → 39 ms swing | **Open — this document** |

For contrast, the same `MapAndOrphanStreamBuffer` machinery pushed **106 MB of SSBO instance data in
57 ms**. The streaming layer is sound; the problem in (1) was per-call overhead on tiny writes, not
bandwidth. Do not read (1) as an indictment of `CgStreamBuffer`.

---

## Reproducing

```bash
./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d"
# leave the window open ~10s; writes harness-output/text-3d/kanji-warmup-profile.csv
```

`TextScene3D` enables `CgProfiler` for an 8-second window (`PROFILE_WINDOW_SECONDS`) and dumps a
per-frame CSV plus per-second scope-tree snapshots. The `doBind.*` scopes live in
`CgMaterial.doBind`; the `streamBuffer.<target>.*` scopes in `CgStreamBuffer.uploadFloats`.

**Caveat on absolute numbers:** the instrumentation is not free. Average FPS in this scene drifted
~113 → ~104 as scopes were added, so per-scope totals are directionally right but slightly inflated.
Re-measure with the instrumentation trimmed before/after any fix. `CgProfiler` is zero-cost when
disabled (single `volatile boolean` check), so the scopes themselves can stay in place.

---

## Re-confirmed 2026-07-28 — independent measurement, same verdict

Re-measured after the atlas merge, the RGBA8 switch, and the packer work. **The finding stands**,
and the two measurements agree closely despite different scene state and a different code base:

| | original (838-frame window) | re-measure (1785 steady frames) | fresh run (658 steady frames) |
|---|---|---|---|
| `doBind.stateSave` mean | ~1.9 ms/frame | **0.960 ms** | **1.487 ms** |
| share of `material.doBind` | — | **96.4%** | **95.1%** |
| share of `drawTotal` | — | **27.1%** | **33.0%** |
| every other `doBind` child | ≤22 ms total | **≤0.008 ms mean** | **≤0.008 ms mean** |

`stateSave` is now the **single largest steady-state cost in the text draw path**. Everything else
that used to compete with it has been fixed: distance-field packing went 1129 ms → 88 ms,
`resolvePlacements` and `glFlush` are ~0 once warm, and glyph upload no longer converts CPU-side.

### Two things to know before acting on this

**1. It may be a harness artifact.** `CgGlState.save` only falls back to real `glGet*` when
`GLStateMirror` is unpopulated, and the mirror is fed by the ASM coremod — absent in the standalone
harness. **Measure in-game before optimising.** If the mirror is populated under Minecraft, this
cost may be small or absent there, and the whole finding applies only to harness runs.

**2. Six slots are saved per bind; the bind does not modify all six.**
`BLEND, DEPTH, CULL, STENCIL, ALPHA_TEST, COLOR_MASK`, at ~4.7 material binds/frame. Narrowing that
set, or caching the save across binds within a frame, is cheaper than any of the four rewrite
options above and should be tried first.

### A near-miss worth recording

This was almost dismissed as fixed. The harness CSV declared `gcCount`/`gcTimeMs` in its header but
never wrote them, shifting every later column left by two — so `dbStateSaveMs` was read from what
was actually `doBind.uboBind` (0.001 ms) and reported as "not reproducing", while the real
`stateSave` value sat under the `gcTimeMs` label and was analysed as a garbage-collection problem
that does not exist (real GC in that run: **0 collections**).

The CSV writer is fixed and now carries a comment about matching field counts. The lesson generalises:
**a misaligned CSV does not error, it silently inverts conclusions.** Check `len(header) == len(row)`
before trusting any analysis built on it.

---

## Re-confirmed 2026-07-29 — and now the only item left in the text stack

Handed off from `docs/font/PERFORMANCE_TODO.md`, which is closed. This is not a text problem and
never was: `CgGlState.save*()` is engine-wide infrastructure, and it only surfaced in text profiling
because text binds a material like everything else does.

**Current numbers**, `text-3d`, after a full day of clearing everything around it:

| | |
|---|---|
| steady state, every frame | **0.773 ms** |
| frame 2 (cold) | **36–59 ms** |
| sporadic mid-run | **13–40 ms** typical |
| **worst observed** | **346.8 ms, immediately followed by 286.5 ms** |

That worst case is the one to design against. Two consecutive frames at t≈4.47 s and t≈4.82 s of a
`text-3d` run measured 349.9 ms and 290.4 ms wall, of which `stateSave` was 346.8 and 286.5 — a
**~0.64 s freeze in the middle of an otherwise 134 fps run**, with nothing else happening in the
scene. It is ~9x the previously recorded ceiling.

Two traps in reading it:

- `quadLoop` reports a matching 283 ms on the same frame. That is not a second problem:
  `material.doBind` fires *inside* the quad loop on a batch transition, so the loop simply inherits
  the stall as the open scope. Attributing it to quad submission would be chasing a shadow.
- It is invisible to percentiles. Median 7.5 ms, p90 8.6 ms — both excellent, both completely
  concealing it. This stall was found by a human watching the screen, not by a summary statistic.
  Count frames over a threshold, and look at the maximum.

Lower than the 1.9 ms/frame average recorded above, because that measurement predated the material
transition fix (1000 binds/frame → 1). What remains is the per-bind cost with the bind count already
minimal — i.e. this is now the floor of the current design, not an amplified symptom.

**Why it is worth acting on now.** It was previously one item among many, and arguably not the
biggest. It is now, unambiguously, the largest remaining cost in the text render path that belongs
to CrystalGraphics code:

- `resolveGlyphs` 0.010 ms · `quadLoop` at its floor (0.136 µs/quad) · whole-frame unaccounted 0.1%
- steady-state real work 2.45 ms/frame, of which `stateSave` is **0.773 ms — roughly a third**

**Everything that used to confound measuring it has been removed.** Each of these was, at some
point, mistaken for the cause of a stall that was actually this:

- glyph re-rasterization (24,833 → 4,815) — a dedup guard released before commit
- placement-cache misses (3406 → 45 glyphs queried/frame) — one empty glyph poisoning whole layouts
- profiler frame boundary off-by-one — post-render work attributed to the following frame
- `draw.glyphCount` read as a total when it is a last-value sample

So a `stateSave` number measured today is trustworthy in a way it was not before. Start from
"Directions for the rewrite" above; the cheap experiment named there — narrowing the saved slot set,
or caching the save across binds within a frame — is still the thing to try first.
