# Text Stack — Performance Record (CLOSED)

> Was "Performance TODO". Renamed because it no longer contains a to-do list — see the banner below.

Status as of 2026-07-29. Every number here was measured, not estimated. Where something was *not*
measured, that is stated explicitly rather than left implied — the unmeasured list is the more
important half of this document.

Benchmarks referenced throughout:

- **text-stress** — `gl-debug-harness --mode=text-stress`. 1000 labels x 20 chars, single font,
  reshaped and redrawn every frame. Deliberately pathological; no real UI reshapes everything every
  frame. Modes: `STATIC`, `SAME_LENGTH`, `VARYING_LENGTH`.
- **text-3d** — `gl-debug-harness --mode=text-3d`. CJK warmup, 3 fonts, ~2100 glyphs generated.

> ⚠️ **The harness caps at `Display.sync(120)` = 8.33 ms/frame** (`InteractiveSceneRunner.TARGET_FPS`).
> Any measurement at or near 8.33 ms is the cap, not the cost. `STATIC` mode sits exactly there and
> its frame number is meaningless — use its `drawMs` (3.87 ms) instead. `SAME_LENGTH` (13.92 ms) and
> `VARYING_LENGTH` (14.46 ms) are above the cap and therefore real.

---

> # ✅ CLOSED — 2026-07-29
>
> **Nothing on this list is open.** Every item is fixed, measured-and-declined, reverted with
> evidence, deferred to a phase that does not exist yet, or handed to the document that owns it.
> This is now **reference material, not a queue.**
>
> | where the items went | |
> |---|---|
> | Fixed | async bitmap fallback, empty-glyph `markEmpty`, glyph re-rasterization (24,833 → 4,815), viewport culling, `breakLines` (31.9 → 19.1 ms), `sliceRun` copy (3.0x), cache byte budgets, hot-reload invalidation, `fontFeatures` + `baselineShift`, worker-thread capture, whole-frame accounting |
> | Measured and declined | `sliceRun` quadratic (~1.3 ms, adversarial only), `graphemeFind` cluster scan, unattributed shaping gaps (mostly profiler overhead), `hbShape` (native floor), `bufFill` UTF-16 migration |
> | Built, measured, reverted | glyph upload batching (~5 µs/frame), adaptive drain budget (cost 2x convergence) |
> | Deleted | `fontFamilyOverride` |
> | Handed off | `doBind.stateSave` → `docs_research/GL_STATE_SYNC_STALL_FINDINGS.md` — GL-state infrastructure, not text |
> | Deferred | CrystalGUI text path and `TextField` → CrystalGUI's own profiling phase; in-game measurement |
>
> **Final state, `text-3d`:** 2.45 ms of real work per frame, median 7.5 ms (134 fps), whole-frame
> unaccounted **0.1%**, bitmap convergence ~0.9 s. `:core:check` green.
>
> **One caveat, and it is not a small one.** ~11 frames of ~1100 exceed 20 ms, and the two worst are
> **349.9 ms and 290.4 ms, back to back at t≈4.5 s** — a visible ~0.64 s stall mid-run, not a startup
> artifact. Both are `doBind.stateSave` (346.8 ms and 286.5 ms), i.e. the handed-off item. `quadLoop`
> shows a matching 283 ms only because `material.doBind` fires inside it on a batch transition and it
> was the open scope.
>
> An earlier version of this banner said "3–5 frames above 33 ms, all in the first 0.4 s". That was
> wrong — it came from reading medians and p90s, which are excellent (7.5 / 8.6 ms) and completely
> hide two catastrophic frames. It was caught by a human watching the screen, which is the third time
> in this document's history that beat the profiler. **Percentiles do not surface a stall you can
> see.** Count frames over a threshold and look at the maximum.
>
> **Before trusting any number here, read "Measurement gotchas".** Six of the conclusions in this
> document were wrong at some point for reasons in that section, and the corrections are recorded
> alongside the findings rather than quietly edited out.
>
> **Reopening rule:** every declined item states the condition under which it becomes worth
> revisiting. If that condition is not met, the item is not a lead — it is a settled question.

---

## NOT profiled — the honest remaining gaps

**The text stack is not profiled "A to Z."** The main shape/wrap/draw paths are, across Latin, CJK,
RTL, BiDi, fallback, synthetic styles and decorations. These are not.

Enumerate coverage rather than trusting a remembered list — two packages below were missed by two
earlier audits precisely because the package list was hardcoded:

```bash
cd core/src/main/java/com/crystalgraphics
for d in $(find text api/text api/font -type d | sort); do
  n=$(ls $d/*.java 2>/dev/null | wc -l); [ "$n" -gt 0 ] || continue
  s=$(grep -l "CgProfiler" $d/*.java 2>/dev/null | wc -l)
  printf "%-28s %2s/%-2s
" "$d" "$s" "$n"
done
```

### Closed since this section was written — do not re-derive these

Everything below was on this list and has since been measured. Kept as a table rather than deleted
because "we never measured X" is the kind of claim that gets repeated from memory long after it
stops being true. Details in the P1–P7 sections further down.

| was listed as unmeasured | outcome |
|---|---|
| `text/richtext` markup parsing | **0.39–0.91 us/label** — parsing is not the cost (P3) |
| `text/render/context` scale resolvers | **negligible**; the tier-jitter worry was unfounded (P4) |
| Atlas LRU page eviction | **0.153 ms per eviction** (P1) |
| Ellipsis | **2.06 ms**; justification does not exist to measure (P5) |
| Variable fonts | **no shipped font has any axis** — engine supports it, no asset tests it (P6) |
| Font hot-reload (F3+T) | **1.79 ms**, non-issue (P7) |
| `worker.rasterizeBitmap` "never called" | resolved — the async path carries all bitmap generation; the scope was invisible because dumps were render-thread-only |
| `wrap.breakLines` unattributed 15.8 ms | attributed and fixed — **31.9 → 19.1 ms** |

### Deferred to CrystalGUI's own profiling phase — not text-stack work

CrystalGUI is not mature enough to profile as a whole yet. When it is, these belong to that pass,
not this document:

- **The CrystalGUI text path** (`cgui-text-stress`, `UIText`'s ~1.3 us/label overhead).
- **Text editing** (`TextField` cursor/selection/clipboard).

### In-game measurement

Still the largest caveat (see below) and still deprioritised: the goal is render-thread leanness and
the harness measures that. `CgProfilerDump` exists for when it matters.

### The largest caveat: everything is harness-only

Every number in this document comes from `gl-debug-harness` — LWJGL3, no Minecraft, no other mods.
**Nothing has been measured in-game**, on either mc1710 (LWJGL2 + ASM coremod + a modpack's worth of
foreign GL state) or mc1201. The coremod's redirect layer, `GLStateMirror`'s effect on state saves,
driver behaviour under a real mod stack, and contention with the game's own render work are all
unmeasured. The harness is the right place to iterate, but it is not evidence about production.

---

## THE LIST — every item and how it was closed

Worked down to zero on 2026-07-29. Struck-through headings are closed and kept in place because
**the reasoning is the point** — a bare "done" tells the next person nothing about whether to
revisit. The "Resolved from the previous list" section holds the rest.

A negative result is only worth having if it is written where the next person looks before repeating
it, so declined and reverted work is recorded here at the same level of detail as the wins.

An earlier revision of this document described the async bitmap fallback as "attempted and reverted"
and "dead code, the biggest render-thread item left." Both were true when written and are now false;
those sections were deleted rather than annotated, because a stale section under a START HERE banner
is worse than no section.

### ~~1. `doBind.stateSave`~~ — HANDED OFF, not a text item

**Closed here 2026-07-29; owned by `docs_research/GL_STATE_SYNC_STALL_FINDINGS.md`.**

`CgGlState.save*()` is engine-wide infrastructure. It surfaced in text profiling only because text
binds a material like everything else does, and fixing it means rewriting `CgGlScope`/
`GLStateMirror` — no part of that is text work. That document already carries the full
investigation; today's numbers and the reasons they are now trustworthy were appended to it.

Retained below for the record, since it was the last thing standing here:

Both a **standing per-frame cost and the worst spike**, which the older write-up (further down,
under "Startup hitches") understates by treating it as one-time:

| | cost |
|---|---|
| every frame, steady state | **0.773 ms** |
| frame 2 (cold) | **36–40 ms** |
| sporadic mid-run (e.g. t=6.8s, t=8.1s) | **13–27 ms** |

`CgGlState.save*()` issues `glGet*` queries which force a pipeline sync — the driver must drain
everything queued before answering. Confirmed from three directions now. The detailed investigation,
including two disproved hypotheses (harness artifact / `GLStateMirror`), is under "Startup hitches"
below; see also `docs_research/GL_STATE_SYNC_STALL_FINDINGS.md`.

### ~~2. `wrap.breakLines` unattributed time~~ — ATTRIBUTED AND FIXED

**Closed 2026-07-29.** The 15.8 ms was `splitAtGraphemeBreaks`, which had no scopes at all. Once
scoped it split into `graphemeCollect` 10.6 ms and `graphemeFind` 4.5 ms, leaving 1.5 ms residue.

The cause was quadratic, and the same shape as item 3: **every grapheme fallback re-scanned the
entire remaining segment** for cluster boundaries. 105 fallbacks over a 3363-character run collected
**49,190 boundaries — 14.6x the 3,363 the text actually contains** — at ~0.19 us each.

Fixed by computing grapheme boundaries **once per paragraph** and having each fallback binary-search
a sub-range of that shared array (`CgReshapeContext.graphemeBoundaries()`). This is exact, not an
approximation: grapheme-cluster boundaries depend only on adjacent characters, so a substring's
boundaries are the global set restricted to its range — and every split point is itself drawn from
that set, so ranges always start cluster-aligned. Deliberately **not** applied to line-break
boundaries, which are genuinely context-sensitive and would change where lines break.

Also landed: `collectBoundaries` grows an `int[]` instead of boxing into `ArrayList<Integer>`, and
both `BreakIterator`s are thread-local and reused rather than constructed per call (they were
constructed 238 times on one cold frame). `CgLineBreaker` is a process-wide singleton and
`BreakIterator` is not thread-safe, hence thread-local rather than a plain field.

**Result: `breakLines` 31.9 → 19.1 ms median of 3 runs (−40%).** Remaining, in order:
`graphemeFind` 4.55, `graphemeCollect` 3.30 (three paragraphs x one full-text scan — this is the
floor), `findBoundary` 2.9, `safeGlyphBoundary` 1.7, `sliceRun` 1.6, `collectBoundaries` 1.7.

**Further fixable, not done:** `graphemeFind` is dominated by `glyphIndexAtCharOffset`'s linear scan
over `clusterIds`, not by arithmetic — prefix-summing the advances was tried and reverted (4.83 →
4.55 ms, inside noise, while adding ~238 array allocations). Binary-searching the cluster lookup is
the change that would matter; `clusterIds` are monotonic, ascending for LTR and descending for RTL.
Left undone because this is **adversarial-only** (realistic text: 0.748 ms for 1000 wrapped labels)
and **cold-path** (0.005 ms per frame after the first, `CgTextLayoutCache` serves the rest).

### ~~3. `sliceRun` quadratic tail copying~~ — NOT WORTH FIXING

**Closed 2026-07-29 as declined, not as done.** The 9.9x copy amplification (66,636 glyphs copied
for 6,726 real) is real and remains.

**What landed:** the copy itself uses `Arrays.copyOfRange`/`System.arraycopy` instead of an element
loop re-invoking five accessors per glyph — **4.53 ms → 1.49 ms, 3.0x**. That captured most of the
available win without touching the algorithm.

**Why the rest is declined.** Making it linear needs `buildFragments` to iterate the original run
with a moving glyph offset instead of slicing a fresh tail per line, threading that offset through
`measurePrefixWidth`, `safeGlyphBoundary` and the **RTL visual-order mirroring**, where the logical
head is the *top* of the array. Measured remaining upside: **~1.3 ms** (1.49 → ~0.15) on a fixture
that runs once and is adversarial by construction. That is a bad trade against restructuring
RTL-sensitive code that has already broken once — and note item 2 above got a larger win from the
same "hoist out of the recursion" idea precisely because grapheme boundaries are context-free and
glyph slices are not.

Do not reopen without a workload where `lineBreak.slicedGlyphs` is large *and* layouts are being
rebuilt every frame. `CgLineBreakerSliceEquivalenceTest` (LTR + RTL) is the guard if it ever is.

### ~~2. Unattributed shaping gaps — ~1.06 ms~~ — MEASURED; MOSTLY THE PROFILER ITSELF

**Closed 2026-07-29.** Scoped it, measured it, removed the scopes again.

What the gap actually contained, from the one instrumented run:

| | ms |
|---|---|
| `hb.unpack` — six per-run array allocations + the O(glyphs) unpack loop | 0.196 |
| `hb.buildRun` — constructing the `CgShapedRun` | 0.033 |
| `hb.substring` | 0.030 |
| **total real work found** | **0.259** |

The other ~0.8 ms is **the cost of looking**. `text-stress` issues ~1000 `shape()` calls per frame,
and the four scopes already inside that method cost ~1.00 ms at ~250 ns each; the three added here
cost another ~0.75 ms. `shape.harfbuzz` duly rose 3.86 → 4.62 ms when they were added.

The control run settled it in the other direction: with the new scopes *removed*, `shape.harfbuzz`
measured **4.909 ms — higher than with them**. Run-to-run variance on this scene exceeds the effect,
so the gap cannot be resolved further by adding instrumentation. This is the measurement floor, not
a hiding place.

**Scopes were removed again** rather than left in: they cost more than they revealed. The finding is
recorded in `CgTextShaper` at the exact code it describes, with a "do not re-add without a workload
that shapes far less often" note.

Two things worth carrying forward:

- **Per-call scopes are not free at this call volume.** ~250 ns each is nothing against a 30 ms
  MSDF generation and significant against a 4 µs shape call. Scope granularity has to match the work.
- **None of this matters in a real UI.** `text-stress` reshapes 1000 labels every frame by design;
  with `CgTextLayoutCache` doing its job, `shape()` barely runs.

### ~~3. Cache hygiene and inert API fields~~ — ALL CLOSED

**Closed 2026-07-29.** Full detail in the dedicated sections further down; summary:

- **Both caches now evict on bytes *and* entries** (8 MB/1024 for `CgGlyphPlacementCache`,
  16 MB/512 for `CgTextLayoutCache`), the dual-budget shape Skia's `SkStrikeCache` uses. A flat
  entry count bounded nothing useful when entries differ in size by orders of magnitude.
- **`CgTextLayoutCache.clear()` is wired into the F3+T reload path** via `CgAssetReloader`.
- **`fontFeatures` and `baselineShift` are wired**; **`fontFamilyOverride` was deleted.**

---

## Resolved from the previous list

### ✅ Viewport culling — shipped

`CgViewFrustum` (`api/render/`, general-purpose, wraps JOML's `FrustumIntersection`) plus
`CgTextCuller` (text-specific policy: layout box, one-layout-height margin, fail-open). Culls per
draw **before** `resolveGlyphs`, so an invisible layout costs nothing rather than being resolved and
then discarded.

Measured by rotating the camera 180° away from the text:

| camera | draws culled | quads emitted | scene time |
|---|---|---|---|
| facing text | 0 | 8,107 | 2.802 ms |
| rotated away | 3 (all) | **42** | **0.043 ms** |

−99.5% quads, −98.5% scene time. Cost when it culls nothing sits inside run-to-run noise (~4 frustum
tests per frame).

> **Correction to the original justification.** The previous entry claimed both paragraphs sat "at
> y=2112, entirely off-screen" in a 600px viewport. That was wrong — y=2112 is a *layout* coordinate,
> and the world pose scales it by 0.0005, putting the text near y≈0.44 in world space, plainly
> visible. With a static camera the culler correctly rejects **nothing**. The feature earns its place
> for a different reason than the one written down: camera movement, which is the normal case for
> world text, not static off-screen layouts.

`CgRenderPipeline` is the other waiting consumer — `CgRenderCommand.worldAabb` is documented as "used
for frustum culling (v2)" and `CgRenderCommandQueue.submit` already validates it as finite and
correctly ordered, so the contract is enforced and unconsumed. Not wired up yet, deliberately.

### ✅ Glyph re-rasterization — 24,833 → 4,815, found by the cross-thread dump

The first cross-thread profile showed **~24,800 bitmap rasterizations for ~7,000 distinct glyphs**.
Sub-pixel buckets were the obvious explanation and were wrong: there are four, but only below
`SUB_PIXEL_BUCKET_MAX_PX` (32), and `resolveSubPixelBucket` returns 0 unconditionally for world
text, which is what `text-3d` draws.

The real cause was the dedup guard being released too early. `pendingJobs.remove(job)` sat in the
worker task's `finally`, clearing "somebody is already making this glyph" the moment generation
*finished* — but a finished glyph is not a usable glyph. It waits in `completedResults` until
`drainCompletedGlyphs` commits it, and that drain is deliberately budget-limited. Throughout that
window the atlas still missed and the guard was already gone, so **every frame re-submitted the same
glyph**, over and over, until the commit finally landed. `commitGeneratedGlyph` then discarded every
duplicate. The multiplier was 3.5x rather than a round number precisely because it is proportional
to how long results wait, not to anything structural.

Fixed by holding the entry until `pollCompleted()` — microseconds before the commit, in the same
drain iteration.

| | before | after |
|---|---|---|
| bitmap rasterizations | 24,833 | **4,815** |
| worker rasterize time | ~2.7 s | **347 ms** |
| max single rasterize | 241.9 ms | **37.6 ms** |
| bitmap convergence | ~0.9 s | ~0.9 s (unchanged) |
| steady-state work/frame | 2.45 ms | 2.45 ms (unchanged) |

**Introduced the same day**, when the bitmap fallback became asynchronous — before that there was no
generate-to-commit window to duplicate inside. It shipped alongside the change everyone was pleased
with, which is the general lesson: making something asynchronous creates windows that did not exist,
and a dedup guard has to cover the whole window, not just the expensive part.

Two corrections it forced: the "241 ms max rasterize is an external stall" reading was wrong — most
of it was four workers duplicating each other's work — and the convergence metric
"cumulative commits >= 7000" was calibrated against the inflated numbers and now reads ~7.4 s for a
run that still converges in 0.9 s. Measure coverage with `msdfFellBackToBitmap` reaching 0, not with
commit counts.

### ✅ Worker-thread profile capture — shipped

`TextScene3D.dumpProfile` now also writes `CgProfilerDump.dumpAllThreads`. Every tree dump was
render-thread-only, which is why `worker.rasterizeBitmap` read as "never called". Totals are
whole-run rather than per-frame (workers never call `endFrame`). The first run surfaced two leads:
**~24,800 bitmap rasterizations** across the pool — check against sub-pixel bucket count before
calling that duplication — and a **241 ms max single rasterize call**, the familiar stall signature.

### ❌ Adaptive drain budget — built, measured, reverted

Scaling the drain budget by remaining frame headroom instead of a flat backlog tier. Full write-up
at `CgFontRegistry`'s `MAX_COMMIT_NANOS_PER_FRAME`. Three reasons, worst first:

1. **The target was a misattribution.** The 89.5 ms drain that motivated it committed *3 glyphs with
   3 uploads and no atlas growth* — 30 ms per upload with no work behind it. An external stall, the
   same signature that exonerated `quadLoop`. No budget can prevent it: the budget is checked
   between commits and only three occurred.
2. **The cost was real and consistent:** convergence 0.77 s → 1.42 s, reproduced at both 8.33 ms and
   12 ms targets. Warmup non-drain work is 7–9 ms on its own, so headroom is ~0 and the budget pins
   to its floor — it does not adapt, it reverts to base.
3. **The benefit was not reproducible:** frames over 15 ms went 19 → 10 at an 8.33 ms target but
   19 → **30** at 12 ms.

The premise had also decayed: "drain flat at 6.5–7.2 ms across ~13 warmup frames" was measured
*before* the placement-cache `markEmpty` fix. Median drain on draining frames is now **2.19 ms** and
those frames are gone.

---

### Not on this list, and why

| | |
|---|---|
| `quadLoop` spikes (133 ms worst) | **Not ours.** 0.136 us/quad; on every spike the slowest single iteration is 2–5 us and *zero* iterations exceed 1 ms, so the whole loop runs ~5x slower with nothing blocking. Cutting MSDF workers 4→1 left the slow-frame count identical at 15. External CPU contention. |
| `wrap.breakLines` as a *defect* | The 24.5 ms is adversarial input, not broken code — 3363 chars with one space, so 105 of 133 splits take the character-level fallback. Realistic text: 0.748 ms for 1000 wrapped labels. Item 2 above is about **attributing** that time, not assuming it is wrong. Check `lineBreak.graphemeFallbacks` first. |
| Glyph upload batching | **Measured and rejected twice.** Worth ~5 us/frame; the drain does 4.6 uploads/frame, not the thousands it was designed for. Full write-up at `CgFontRegistry.drainCompletedGlyphs`. |

---

## Measurement gotchas — read before trusting any number here

Every one of these produced a wrong conclusion at least once, and each cost a diagnosis cycle.

1. **`asyncCommit.glyphsUploaded` counts commits *attempted*, not uploads *performed*.**
   `commitGeneratedGlyph` early-returns for glyphs already resident or with empty geometry. Measured
   61,787 counted vs. **3,752 actual** `glTexSubImage3D` calls — a 16x gap. Reading it as upload
   volume is what made a 4.6-per-frame trickle look like a flood and justified an optimisation worth
   5 us/frame. Use `texSubImageCalls`.
2. **`draw.glyphCount` is a SAMPLE (last draw only), not a per-frame total.** It read 45 while the
   frame actually emitted 8,103 quads. Mistaking it for a total makes `quadLoop` look ~150x more
   expensive per glyph than it is. Use the `draw.quadsEmitted` counter.
3. **Nested scopes double-count under recursion.** `lineBreak.graphemeFallback` recurses into
   itself via `buildFragments`, and summing every path gave 154.9 ms against a 30.4 ms parent. For
   self-recursive paths use a counter, not a scope total.
4. **A scope total cannot distinguish a blocking call from preemption.** Identical aggregate,
   opposite fixes. `-Dcrystalgraphics.text.traceQuadLoop=true` times each iteration and reports the
   slowest; that is what exonerated `quadLoop`.
5. **Frame time is capped by `Display.sync(120)`.** Since the resolve-path fixes, savings land in
   `frame.sync` (2.17 → 4.02 → 5.35 ms idle) and `frameDt` barely moves. Judge work by
   **`frameDt − sync`**, not by fps.
6. **Run-to-run contention is real and large.** One `text-3d` run measured 30 fps and 23% of frames
   over 33 ms; clean runs of the same build measured 110 fps and 0.3%. Back-to-back harness runs
   contend for the GPU. Never conclude from a single run.

### Instrumentation added 2026-07-29

- **Whole-frame accounting.** `InteractiveSceneLifecycle.onFrameEnd(ctx, frame)` fires after swap
  and sync with the frame's true wall duration. Previously the scene closed its profiler frame
  inside `render()`, so overlay/`onFrameRendered`/swap/sync landed in the *next* row while
  `getDeltaTime()` reported the *previous* frame — a row could show a 117 ms draw against a 7 ms
  delta. Loop steps are now scoped `frame.worldPass`/`textContext`/`scene`/`overlay`/
  `onFrameRendered`/`swap`/`sync`, with `accountedMs`/`unaccountedMs` columns. Unaccounted is now
  **0.008 ms mean, 0.1% of frame time**; it was 60–110 ms on warmup frames.
- **`swap` and `sync` are deliberately separate** — a large `swap` means the GPU is behind, a large
  `sync` means the frame finished *early*. Summing them makes an idle frame look stalled.
- Flags, both off by default: `-Dcrystalgraphics.text.traceQuadLoop=true`,
  `-Dcrystalgraphics.text.msdfWorkers=N` (the latter is what made "are our workers preempting the
  render thread?" testable — answer: no).

---

## Coverage state — what remains, enumerated

Re-audited by enumeration (not memory). Uninstrumented files were then checked individually: in
`api/font`, `api/text` and `text/cache` every remaining one is a record, key, enum or data holder
with no executable path — `CgFontFamily.resolveRuns` is covered by `shape.resolveRuns` in its
caller, `CgBakedGlyphs` by `wrap.bakeGlyphs`, and so on.

One real gap was found and closed: **`CgWorkerFontContext.generateBitmap` was uninstrumented.** The
`freetype.rasterize` scope covers only the *synchronous* render-thread fallback in `CgFontRegistry`;
the same work on a background worker had no scope, so a cross-thread report showed MSDF generation
but none of the bitmap glyphs generated beside it. Now `worker.rasterizeBitmap`.

### Instrumented but never exercised — known-unknowns, not blind spots

| path | state |
|---|---|
| ~~`worker.rasterizeBitmap` never called~~ | **RESOLVED 2026-07-29.** The async bitmap path now carries essentially all bitmap generation; the sync fallback runs only when a job has already failed or the queue rejects a submission. Render-thread `freetype.rasterize` went 387 ms / 4810 calls → ~10 ms / 13 calls. Note the scope is worker-thread, so it is invisible in the render-thread tree dumps — needs `CgProfiler.reportAllThreads()`. |
| variable font axes | No shipped font has any (all five checked). Engine supports it; no asset tests it. |

### Not done

| item | why |
|---|---|
| **CrystalGUI-side whole-frame audit** | `text-3d` got full frame accounting on 2026-07-29 (unaccounted 60–110 ms → 0.1%), and that audit found two real bugs. **The CrystalGUI text path has had no equivalent pass.** `cgui-text-stress` is the scene; `UIText` is only characterised as "~1.3 us/label overhead". Highest-value unmeasured item now that the CrystalGraphics side is clean. |
| In-game measurement | Deprioritised — the goal is render-thread leanness, and the harness measures that fine. `CgProfilerDump` exists for when it matters. |
| Text editing (`TextField`) | Needs synthetic input driving a scene; CrystalGUI-side, not text engine. Lowest value on the list. |
| ~~Worker-thread profile capture~~ | **DONE 2026-07-29.** `TextScene3D.dumpProfile` now also writes `CgProfilerDump.dumpAllThreads`. |

**This is a different state from "done".** Nothing is unmeasured-and-unknown; the items above are
characterised, and most are deliberate stops rather than gaps.

---

## DONE — P2 (tooling), P6, P7 (2026-07-29)

### P6 — Variable fonts: **no shipped font has any axis**

`CgVariableFontCostTest` checked all five bundled fonts: IBMPlexSans, MPLUS1p, NotoSansArabic,
MPLUSRounded1c, IBMPlexSansArabic — **0 variation axes between them**. The engine supports variable
fonts (`CgFontVariation` participates in `CgFontKey`), but no asset exercises that support, so it is
untested by anything real.

What *is* measurable is the floor any axis step must pay. An axis value is part of the font key, so
changing it means a new key, a new native face, and a glyph cache sharing nothing with the previous
value:

| | |
|---|---|
| face load | **0.316 ms** |
| plus | re-rasterising every on-screen glyph (~0.08 ms each) |

**Continuous axis animation is not viable** — quantise to a few discrete steps and let the glyph
caches settle on each. To close this properly, add a variable font (e.g. IBMPlexSans-Variable) to
the test assets and re-run the test, which already handles that case.

### P7 — Hot-reload: **1.79 ms**. Non-issue, as expected

`CgAssetReloader.reload()` (the F3+T path) instrumented per stage — `assetReload.textures`,
`.shaders`, `.materials` — and triggered once mid-run from `text-stress` after the atlas has
converged, since it needs a live GL context and cannot be a unit test.

**1.79 ms total.** Not worth further attention. Stage scopes are in place if that ever changes.

### P2 — In-game measurement: tooling built, **measurement still outstanding**

The reason in-game profiling never happened was not that the profiler does not work there — it does
— but that a loader had no way to get results out. The harness writes files at scene end; nothing
equivalent existed for mc1710/mc1201.

`CgProfilerDump` (in `core`, platform-agnostic) fixes that:

```java
CgProfiler.setEnabled(true);                                   // startup or toggle key
CgProfilerDump.dumpAllThreads(new File("crystalgraphics-profile"), "ingame");
```

Use `dumpAllThreads` — glyph generation runs on background workers, and a render-thread-only report
shows none of it, which is exactly how MSDF generation stayed invisible for so long. Output is
timestamped rather than overwritten, so two sessions can be compared. Never throws.

**Still outstanding, and it needs a human:** wiring that call to a keybind or command in `mc1710`
(and `mc1201`), then playing a real session. Specifically worth checking in-game:

- `doBind.stateSave` with the ASM coremod live — the harness says all 6 slots `glGet`
  unconditionally because they are not mirror-backed; confirm that holds in production
- `freetype.rasterize` under genuine CPU contention from the game and other mods
- whether the `Thread.MIN_PRIORITY` glyph-worker change behaves the same under the game's own
  thread pressure

Until that run happens, **every number in this document is directional for production, not
evidence.**

### NOT DONE — text editing (`TextField`)

Deliberately skipped rather than half-measured. Profiling cursor/selection/clipboard needs synthetic
input driving a real `TextField` over many frames — a scene, not a scope — and it is CrystalGUI-side
rather than text-engine. Lowest value of anything on this list; worth doing only if text input
becomes a real workload.

---

## DONE — P1, P3, P4, P5 measured (2026-07-29)

Four of the seven work-plan items are closed. **None found a performance problem**; all four were
blind spots rather than bottlenecks, which is worth knowing precisely because they can now stop being
guessed about.

### P1 — Atlas page eviction: 0.153 ms per eviction

`CgGlyphAtlasEvictionCostTest` forces it with a 4-page 256x256 atlas rather than a huge glyph set —
eviction cost depends on what a page holds, not on how long it took to fill.

| | |
|---|---|
| evictions | 36 over 4000 allocations |
| **per eviction** | **0.153 ms** |
| glyphs dropped per eviction | **100** |
| share of allocation time | 15.1% |

Well under a frame, so eviction itself does not stall. The number to keep an eye on is the 100
glyphs dropped: each invalidates a `CgGlyphPlacementCache` entry, and if those glyphs are still
on-screen they re-rasterise at ~0.08 ms each — ~8 ms if all 100 came back at once. In practice LRU
evicts the *coldest* page, so by construction they are the glyphs least likely to return. Measured
in test mode (`skipGlUpload`), so this is the CPU side only; the GL texture work an in-game eviction
also does is not included.

### P3 — Markup parsing: 0.39-0.91 us/label, and parsing is not the cost

| mode | parse | reshape | vs LATIN reshape |
|---|---|---|---|
| LATIN_BASELINE | — | 7.84 ms | — |
| MARKUP_HTML | **0.91 ms** | 14.97 ms | +7.1 ms |
| MARKUP_MINECRAFT | **0.39 ms** | 11.45 ms | +3.6 ms |

The parser is cheap. Markup's real cost is the **extra runs its spans create** — style spans split
the paragraph, and each fragment shapes separately, exactly as `SYNTHETIC_STYLE` and `DECORATIONS`
already showed. Parsing accounts for 13% of HTML markup's overhead and 11% of Minecraft's.

`CgMinecraftColorCodeParser` — the realistic case, since every chat line in a MC mod hits it — is the
cheaper of the two at **0.39 us/label**. Not a concern.

### P4 — Scale resolvers: negligible, and the jitter worry was unfounded

| | |
|---|---|
| `scaleResolver.extractMaxScale` | 0.000-0.031 ms/call |
| `scaleResolver.ortho` | 1000 calls/frame (one per label) |
| `scaleResolver.perspective` | 3 calls/frame in text-3d |

`PerspectiveScaleResolver.resolveEffectiveTargetPx` is three arithmetic ops returning a clamped
constant tier — no matrix work at all. The concern that it might jitter `effectiveTargetPx` frame to
frame and miss the placement cache is **structurally impossible**: the value cannot vary for a given
`baseTargetPx`. `OrthographicScaleResolver` does real work (`extractMaxScale` + hysteresis) but at
under 3 us worst case. Both closed.

### P5 — Ellipsis: 2.06 ms; justification does not exist

`shape.ellipsis` read 0.03 ms in every prior benchmark because **no scene enabled it** — it was
timing an untaken branch. Forced to truncate (`maxLines(1)` + marker), it is **2.06 ms** for 1000
labels, ~2.1 us/label. Real but modest.

**Justification is not applicable.** `CgTextAlign.JUSTIFY` is deliberately omitted — text
justification is out of scope for the engine — so there is no feature to benchmark. The
`wrap.justify` scope measures alignment offset application, not justification. **Remove this from any
future coverage list.**

### Coverage after this pass

`text/richtext` 2/4 and `text/render/context` 2/5 instrumented (the uninstrumented remainder is
`CgMarkupParseException`, `CgMarkupParser` the interface, `CgTextRenderContext`, and
`CgTextScaleResolver` the interface — no executable hot path between them).

---

## WORK PLAN — what full A-Z coverage actually requires

Each item below states what to instrument, what benchmark content is missing, and how to know it
worked. Ordered by what can actually hurt a user, not by size.

---

### ~~P1. Atlas LRU page eviction~~ — DONE, 0.153 ms/eviction (see above)

Everything else here is startup or niche. This one runs during normal play, once an atlas fills.

- **Instrument** `CgGlyphAtlas.evictColdestPageAndFreeLayer()` (line ~747) with a scope, plus
  counters for evictions and for glyphs invalidated per eviction. `evictionGeneration` already
  exists and is read by `CgGlyphPlacementCache` — count how many placement-cache entries an
  eviction invalidates, since that is the real downstream cost, not the page free itself.
- **Benchmark**: no existing scene fills an atlas. Add a mode to `text-feature-stress` that walks a
  large CJK range (several thousand distinct glyphs) so pages fill and evict, or shrink page size /
  max layers via `CgMsdfAtlasConfig` to force it sooner. Forcing it with a small cap is cheaper to
  run and equally valid.
- **Done when**: eviction cost per event is known, and the frame containing an eviction is compared
  against a steady frame. Watch for a cascade — an eviction invalidating placement-cache entries
  causes re-resolves, which can cause more atlas traffic.

### ~~P2. In-game measurement~~ — TOOLING DONE (`CgProfilerDump`); the run itself still needs a human

Every number in this document is `gl-debug-harness`: LWJGL3, no Minecraft, no other mods.

- **Instrument**: nothing new. `CgProfiler` already works in-game; it needs a dump path. Wire a
  keybind or command in `mc1710` (and mc1201) that calls `CgProfiler.reportAllThreads()` and writes
  the same CSV/tree the harness does.
- **Measure specifically**: `doBind.stateSave` with the ASM coremod live (harness says 6 non-mirror
  slots always `glGet` — confirm that in-game), `freetype.rasterize` under real CPU contention, and
  frame cost with a modpack's worth of foreign GL state.
- **Done when**: the harness numbers are confirmed or contradicted on at least one real 1.7.10 run.
  Until then treat every figure here as directional for production.

### ~~P3. `text/richtext` — markup parsing~~ — DONE, 0.39-0.91 us/label (see above)

`CgMarkupParser`, `CgTagMarkupParser`, `CgMinecraftColorCodeParser`. **No benchmark passes markup at
all**, so `CgTextLayout.Request.markup(...)` has never executed under measurement.

- **Instrument**: a scope in each parser's `parse(...)`, plus a counter for spans produced.
- **Benchmark**: add a `MARKUP` mode to `text-feature-stress` using `CgTagMarkupParser` and a
  `MINECRAFT_COLOR_CODES` mode using `CgMinecraftColorCodeParser` — the latter matters most, since
  MC-style color codes are the common case in a Minecraft mod and every chat line would hit it.
- **Done when**: parse cost per label is known relative to the 7.9 ms reshape baseline, and it is
  clear whether parsing is cached alongside the shaped paragraph or re-run per frame.

### ~~P4. `text/render/context`~~ — DONE, negligible (see above)

`CgTextRenderContext`, `CgTextScaleResolver`, `OrthographicScaleResolver`,
`PerspectiveScaleResolver`, `ProjectedSizeEstimator`.

`PerspectiveScaleResolver` runs **every frame for every world-space label** in `text-3d` and is
currently only visible as unattributed time inside its callers. It also drives
`effectiveTargetPx`, which decides placement-cache hits — a resolver that jitters by one pixel
misses the cache every frame, and `placementCache.miss.effectiveTargetPx` was added earlier
specifically to watch for that.

- **Instrument**: scope `resolve(...)` on both resolvers and `ProjectedSizeEstimator`.
- **Benchmark**: `text-3d` already exercises perspective. Add an ortho comparison so the two
  resolvers can be compared.
- **Done when**: resolver cost is known, and `effectiveTargetPx` stability under camera motion is
  confirmed (it should step, not jitter).

### ~~P5. Knobs no benchmark enables~~ — DONE; ellipsis 2.06 ms, justification does not exist

`shape.ellipsis` (~0.03 ms) and `wrap.justify` are measured at idle, not under load — no scene turns
them on, so those numbers mean nothing.

- **Benchmark**: add modes to `text-feature-stress` setting `CgParagraphKnobs.maxLines` +
  `ellipsisMarker` (forces truncation and marker append), and one enabling justification.
- **Done when**: both have real numbers against `LATIN_BASELINE`.

### ~~P6. Variable fonts~~ — DONE: no shipped font has axes; face-load floor 0.316 ms

Variation axes re-instantiate native faces and invalidate glyph caches by font key.

- **Benchmark**: a mode that animates a weight axis, which is the realistic worst case (a new font
  key per step, so every glyph re-rasterises).
- **Done when**: the cost of a variation change is known, and it is clear whether an animated axis
  is viable or must be quantised.

### ~~P7. Font hot-reload~~ — DONE, 1.79 ms. Text editing deliberately not done (see above)

- **Hot-reload**: `CgAssetReloader` — measure the stall when `CgFontRegistry`/atlases are dropped
  and rebuilt. One-time, but user-visible and easy to trigger accidentally.
- **Editing**: `TextField` cursor/selection/clipboard. CrystalGUI-side; worth a scene only if text
  input becomes a real workload.

---

### Method note — how the two audit misses happened

Coverage was twice reported complete while `text/richtext` and `text/render/context` had zero
instrumentation, because the audit used a **hardcoded package list** rather than enumerating
directories. Always enumerate:

```bash
cd core/src/main/java/com/crystalgraphics
for d in $(find text api/text api/font -type d | sort); do
  n=$(ls $d/*.java 2>/dev/null | wc -l); [ "$n" -gt 0 ] || continue
  s=$(grep -l "CgProfiler" $d/*.java 2>/dev/null | wc -l)
  printf "%-28s %2s/%-2s
" "$d" "$s" "$n"
done
```

Instrumented-file count is a coarse proxy — a file can have a scope and still leave its hot path
uncovered — but it reliably catches the case that actually bit here: an entire package nobody
remembered existed.

---

## Measured 2026-07-29 — full breakdown of ranked items 0–6

Everything in the ranked table below was instrumented and measured. Headline corrections first:

**Item 0 was a harness bug, not uninstrumented code.** The "~1.9 ms of `draw` attributed to nothing"
was `submitSortedQuads`, which had a scope all along — the stress scene's CSV read a scope named
`submitBatchedQuads`, which does not exist, so the column reported 0.000 rather than its real cost.
Fixed; it reads **1.82 ms** (SAME_LENGTH) / **2.03 ms** (STATIC).

**Text draws through `CgQuadRenderer`, not `CgBatchRenderer`.** Scopes added to the latter read zero.
The real GPU tail is `quadRenderer.flush`.

**The GPU submission path is nearly free.** All 17,000 glyph instances go out in **one** instanced
draw call per frame (`qrFlushes` = 1), and `quadRenderer.drawInstanced` is **0.004 ms**. The whole
upload+bind+draw tail is ~0.27 ms of a 13.58 ms frame. Text is CPU-bound, not draw-call-bound.

### text-stress, 1000 labels x 20 chars (~17,000 instances/frame)

| scope | STATIC | SAME_LENGTH | VARYING_LENGTH |
|---|---|---|---|
| **frameDt** | 8.33 *(at 120 fps cap)* | **13.58** | **14.17** |
| reshape | 0.023 | 7.900 | 8.544 |
| — shape.runs | — | 4.909 | 5.139 |
| — — shape.collectRuns | — | 4.470 | 4.682 |
| — — — shape.harfbuzz | — | 3.914 | 4.056 |
| — — — — hb.shape | — | **2.782** | 2.883 |
| — — — — hb.bufferFill | — | 0.377 | 0.418 |
| — — — — hb.readBack | — | 0.188 | 0.192 |
| — — — — hb.bufferCreate | — | 0.091 | 0.072 |
| — shape.resolveRuns | — | 0.327 | 0.391 |
| — wrap.breakLines | — | 0.707 | 1.015 |
| — wrap.bakeGlyphs | — | 0.354 | 0.392 |
| — wrap.justify | — | 0.295 | 0.311 |
| — shape.bidi | — | 0.217 | 0.227 |
| — wrap.breakBoundaries | — | 0.190 | 0.206 |
| **draw** | **3.930** | **5.402** | 5.413 |
| — resolveGlyphs | 0.396 | 2.357 | 2.372 |
| — — resolvePlacements | ~0 | 0.966 | 0.983 |
| — — flatten | ~0 | 0.553 | 0.517 |
| — — placementCache.lookup | 0.139 | 0.156 | 0.145 |
| — submitSortedQuads | 2.029 | 1.819 | 1.823 |
| — — quadLoop | 1.188 | 1.131 | 1.147 |
| — — — material.doBind | 0.508 | 0.416 | 0.403 |
| — — — — doBind.stateSave | 0.467 | 0.378 | 0.379 |
| — — sortKeys | 0.097 | 0.089 | 0.096 |
| — — syncProjection | 0.079 | 0.089 | 0.085 |
| — glFlush | 0.837 | 0.697 | 0.686 |
| **quadRenderer.flush** | **0.295** | **0.268** | 0.271 |
| — quadRenderer.upload | 0.283 | 0.261 | 0.265 |
| — — streamBuffer.ssbo.write | 0.126 | 0.128 | 0.127 |
| — — streamBuffer.ssbo.commit | 0.101 | 0.085 | 0.090 |
| — — streamBuffer.ssbo.map | 0.052 | 0.047 | 0.047 |
| — quadRenderer.drawInstanced | **0.007** | **0.004** | — |
| placementCache hit / miss | **1000 / 0** | 0 / 1000 | 0 / 1000 |
| material transitions | 1 | 1 | 1 |
| draw calls (qrFlushes) | 1 | 1 | 1 |

### What this says

- **STATIC is the realistic UI number.** With retained layouts and a 100% placement-cache hit rate,
  1000 labels cost **3.93 ms of draw and 0.023 ms of reshape**. Its 8.33 ms frame is the harness cap,
  not a cost. This is the regime real UI lives in, and it is already cheap.
- **Reshaping is 58% of the pathological frame**, and 2.78 ms of that (35% of reshape) is the native
  `hb_shape` call itself. Everything Java-side around it totals ~5.1 ms.
- **`resolveGlyphs` 2.36 ms** = `resolvePlacements` 0.97 + `flatten` 0.55 + cache lookup 0.16, with
  **~0.68 ms still unattributed** inside it. That residue is now the largest single unexplained item.
- **The unattributed shaping gaps** (item 3) resolve to: `shape.runs` -> `collectRuns` 0.44,
  `collectRuns` -> `harfbuzz` 0.56, `harfbuzz` -> its hb.* children 0.48. About 1.5 ms total of
  bookkeeping around shaping.
- **`quadLoop` 1.13 ms contains `material.doBind` 0.42**, so its own per-glyph work is ~0.34 ms for
  17,000 quads (~20 ns/quad). Near the floor.
- **`bufFill` (item 5) is 0.377 ms** — confirming the UTF-16 change is worth ~0.38 ms at best, and it
  is still gated on the cluster-index correctness question. Low priority.

### item 6 — CrystalGUI UIText layer

`cgui-text-stress` at 1000 labels vs `text-stress` at 1000 labels:

| mode | pure CgTextRenderer | via UIText | delta |
|---|---|---|---|
| STATIC | 8.33 *(capped)* | 8.34 *(capped)* | — |
| SAME_LENGTH | 13.58 | 9.76 | **-3.8** |
| VARYING_LENGTH | 14.17 | 16.94 | **+2.8** |

**These two scenes are not strictly equivalent, so do not read the deltas as pure overhead.** UIText
is *faster* on SAME_LENGTH because it does not force a reshape when the text is unchanged, whereas
the pure scene reshapes unconditionally — that difference is the whole point of the caching work
below, showing up early. VARYING_LENGTH is the closer comparison (both genuinely reshape) and puts
the UI layer at roughly **+2.8 us/label**.

---

## Current measured state — text-stress, SAME_LENGTH

13.92 ms/frame for 1000 labels x 20 chars = ~20,000 glyphs reshaped *and* drawn per frame, i.e.
**13.9 us per label** (7.9 us reshape + 5.8 us draw). Fully accounted:

```
frameDt              13.92
├─ reshape            7.94  (57%)
│  ├─ shapeRuns       4.92
│  │  └─ shapeCollect 4.44
│  │     └─ shapeHarfbuzz 3.86
│  │        └─ hbShape 2.79
│  ├─ wrapBreakLines  0.74
│  ├─ wrapBake        0.33
│  ├─ wrapJustify     0.31
│  └─ shapeBidi       0.25
└─ draw               5.77
   ├─ resolveGlyphs   2.70
   ├─ quadLoop        1.17
   ├─ matDoBind       0.43
   └─ dbStateSave     0.39
```

Add ~1.3 us/label on top when going through CrystalGUI's `UIText` rather than `CgTextRenderer`
directly (7.84 vs 6.54 us/label measured).

---

## FIXED (2) — RTL width *measurement* was also re-shaping; BiDi's cost is inherent

Follow-up to the RTL slicing fix below. Counting HarfBuzz calls rather than timing them showed RTL
issuing **4000 `hb.shape` calls against only 2000 runs** — two thousand shapes coming from outside
shaping altogether.

Source: `measurePrefixWidth` had the same LTR-only bail that `safeGlyphBoundary` did. Choosing a
break point binary-searches candidate boundaries and measures each one's prefix width; under LTR that
reads the already-shaped advances, but under RTL it re-shaped the prefix through HarfBuzz *purely to
learn its width*. Twice the shaping the text itself needed, spent on measurement that was thrown
away.

Fixed by mirroring it the same way as the split (logical prefix is the top of the array; safe-break
flag read at `glyphIndex - 1`).

| metric | original | after slicing fix | **after measure fix** |
|---|---|---|---|
| `hb.shape` calls | 4000 | 4000 | **2000** — the true minimum, 2 runs/label |
| `wrap.breakLines` | 13.25 ms | 7.32 ms | **1.27 ms** |
| `hb.shape` | 13.02 ms | 8.79 ms | **4.68 ms** |
| RTL frame | 31.55 ms | 24.43 ms | **18.83 ms** |

**`wrap.breakLines` is down 90%**, from 20x Latin to 1.8x. Total RTL frame improvement across both
fixes: **-40%**.

The test hook `sliceFastPathDisabled` now gates measurement as well as slicing, so
`CgLineBreakerSliceEquivalenceTest` covers both paths — measurement decides *which* boundary is
chosen, so a wrong fast measurement corrupts layout exactly as a wrong slice would. Still 36 LTR +
24 RTL/BiDi combinations identical.

### CLOSED — BiDi's 5.08 ms was more runs, not per-run overhead

The earlier "BiDi pays per-run HarfBuzz buffer overhead" lead was **wrong framing**. Call counts:

| | LATIN | RTL | BIDI | FALLBACK |
|---|---|---|---|---|
| `hb.shape` calls | 1000 | 2000 | **5000** | 5000 |
| `hb.bufferFill` per call | 0.44 us | 0.35 us | **0.31 us** | 0.27 us |

BiDi issues 5x the shape calls because mixed-direction text *must* split into separate runs — and
each call is *cheaper* than Latin's, not more expensive, because the runs are shorter. There is no
per-run overhead to reclaim. Buffers are already thread-local pooled. Inherent; closed.

---

## RESOLVED — the "structural dirty-flag caching" work is already implemented

Listed for a long time as the biggest remaining win: *dirty-flag `CgTextLayout` so unchanged labels
skip reshape entirely*. **It already exists**, in two layers, and measurement confirms both work.

- **Shape stage** — `UIText.ensureShaped()` retains a `CgShapedParagraph` and rebuilds it only when
  the text string or the resolved `CgFontFamily` instance actually changed. Family comparison is by
  reference on purpose: `FontFamilyCache.resolve` is keyed by `(paths, targetPx)`, so an unchanged
  font returns the identical instance.
- **Wrap stage** — `CgShapedParagraph.layout(maxWidth, maxHeight)` memoizes its last result and
  re-runs line breaking only when those values change.

Measured hit rates (`paragraphLayout.hit` / `.miss`, cgui-text-stress, 1000 labels):

| mode | hits/frame | misses/frame | hit rate |
|---|---|---|---|
| STATIC | 0.0 | 0.1 | — essentially **zero calls at all** |
| SAME_LENGTH | 0.0 | 1000.1 | 0% (text changes every frame — a miss is correct) |
| VARYING_LENGTH | 1000.0 | 1000.2 | 50% |

**STATIC — the regime real UI lives in — does essentially no shaping and no wrapping.** That is the
whole goal of the proposed work, already achieved.

A suspected defect was also checked and **disproven**: `UIText` calls `layout(...)` from two places
(`recompute` with `maxWidthForWrap`, `paintOverlay` with `contentWidth`), and the memo holds one
entry, so differing widths would thrash it to a 0% hit rate. VARYING_LENGTH's *exactly* 50% —
two calls per label, the second hitting — proves both sites pass the same width. No thrashing.

### `CgTextLayoutCache` is bypassed by CrystalGUI, and that is correct

Its hit rate reads 2/2 because `UIText` never uses it: it shapes via `CgTextLayout.of(...).shape()`
directly and retains the result per element. That is strictly better than the global cache for this
use — keyed by element identity rather than content, with no LRU eviction to lose entries to. The
global cache exists for direct `draw().text(String)` callers (e.g. `TextField`), which is a different
access pattern. **Nothing to fix; the two caches serve different callers by design.**

---

## FIXED — the FreeType rasterize spike was preemption, not work

`freetype.rasterize` averaged 80 us/glyph but peaked at **9.4 ms** in one run and **51 ms** in
another — the same operation, five times apart, which is the tell that it was never a cost of the
operation at all.

Two appealing hypotheses were measured and **both disproven**:

- **Deferred FreeType table parsing on first glyph load.** `CgFontFirstGlyphCostTest` measures the
  first load from a fresh face against steady state: 0.040 ms vs 0.004 ms on Latin, and under
  0.01 ms on CJK and Arabic. Large ratio, trivial absolute — nowhere near 51 ms.
- **GC pause.** `-Xlog:gc` over a full run: 5 collections, worst pause 6.5 ms, and the first at
  1.121 s — *after* frame 1 finished. Frame 1 has no GC at all.

The actual cause is CPU contention. Frame 1 rasterizes the entire visible text set — **~4800 glyphs,
4793 of the 4806 total calls** — on the render thread, while four MSDF workers saturate every core.
The render thread simply gets descheduled mid-call.

**Fix: glyph generation workers now run at `Thread.MIN_PRIORITY`.** This work is never on the
critical path (a glyph that is not ready renders as a bitmap fallback), so it must never preempt
rendering.

| | before | after (3 runs) |
|---|---|---|
| `ftRaster.loadGlyph` **max** | 50.97 ms | **0.98 / 0.40 / 0.64 ms** |
| `ftRaster.loadGlyph` total | 198.7 ms | ~125 ms (**-37%**) |
| atlas convergence | 9.3 s | 9.4 s (unchanged) |

The variance collapse is the confirmation: 9.4 vs 51 ms before, 0.40–0.98 ms after — fifty times
lower *and* consistent. Convergence did not regress, so the lower priority costs nothing.

Priority is a hint the OS may ignore, so this narrows the window rather than closing it. It cannot
make anything slower: these threads are throughput work with no deadline.

**Note for future spike-hunting:** the harness now accepts ad-hoc JVM flags —
`./gradlew :gl-debug-harness:runHarness --args="--mode=text-3d" -Pharness.jvmArgs="-Xlog:gc"`.

---

## Startup hitches — one fixed, one root-caused and not fixable by warmup

`CgGraphicsLifecycle.initContext` now calls `warmUpDeferredStartupCosts()`, paying lazily-triggered
one-time costs before any frame renders. This does not make startup faster; it moves cost off frames
the user is watching.

### FIXED — JNI library load, 130 ms off frame 1

`NativeLoader.ensureLoaded()` is now called at init. The native FreeType/HarfBuzz/msdfgen library was
loading lazily on whichever call first touched a font, which was inside frame 1's first
`CgFont.load`.

| | before | after |
|---|---|---|
| `font.loadNative` on frame 1 | 130.18 ms | **9.03 ms** |
| frame 1 total | 336.1 ms | **123.8 ms** |
| wall time to end of frame 2 | 1.06 s | **0.84 s** |

### NOT FIXED — frame 2's ~155 ms is a GPU sync, and the shader hypothesis was wrong

I expected frame 2's stall to be shader variant compilation and pre-warmed `TEXT_MATERIAL`
accordingly. **It made no difference**, because compilation was never the cause:
`material.getOrCompileVariant` is **0.633 ms**.

The actual cost is `doBind.stateSave` — **153.026 ms of the 153.230 ms** in `material.doBind`.
`CgGlState.save*()` issues `glGet*` queries, and those force a pipeline sync: the driver has to
finish everything queued (frame 1's atlas uploads and first real draws) before it can answer. The
same scope costs 0.85 ms in steady state, so this is a one-time sync, not a standing cost.

**Warmup cannot fix this.** It is the CPU waiting on GPU work that has already been submitted —
moving the wait earlier just moves the stall.

**Investigated 2026-07-29; both of my proposed avenues turned out not to apply.**

1. **"It may be a harness artifact because `GLStateMirror` is unpopulated there."** Wrong, twice
   over. `GLStateMirror` is indeed only populated by `CrystalGLRedirects`, the **mc1710 ASM
   coremod** — mc1201 (Forge/NeoForge/Fabric) has no redirect either, so the harness is
   representative of MC 1.20.x, not unrepresentative. But more decisively: the mirror is irrelevant
   *at this call site*. `CgGlState.save` passes `useGlGet` only to `FboState` and `ProgramState` —
   the only two slots that can consult the mirror — and this save requests **BLEND, DEPTH, CULL,
   STENCIL, ALPHA_TEST, COLOR_MASK**, none of which are mirror-backed. They `glGet` unconditionally
   on every platform, mc1710 included.
2. **"Save fewer slots."** Already done — it saves 6, not `saveAll()`'s 16.

So the remaining cost is 6 unavoidable `glGet` calls per material bind. The frame-2 spike is those
calls syncing on frame 1's outstanding GPU work, which is one-time and not removable. Steady state
is 0.38-0.47 ms per frame for a single bind — acceptable, and already ~200x better than before the
material-transition fix (90.7 ms/frame when it ran per `draw()`).

A real fix would mean extending `GLStateMirror` to cover blend/depth/cull/stencil state and
populating it from CG's own setters. That is safe in the harness but not in-game, where other mods
change GL state outside CG's knowledge — which is the entire reason the coremod exists. Out of scope
for a text-performance pass; noted for whoever owns the state layer.

The material pre-warm was kept even though it did not fix the stall: it compiles *both* `MSDF_MODE`
variants, so neither compiles mid-frame later when a bitmap-fallback glyph and an MSDF glyph appear
in the same frame. Cheap, and it removes a real (if small) future hitch.

---

## §4 BUILT AND MEASURED — `text-feature-stress`, and one real defect fixed

New benchmark: `gl-debug-harness --mode=text-feature-stress`. Six modes, 1000 labels each, matching
text-stress so numbers are comparable. It exists because both existing benchmarks draw plain,
single-font, LTR Latin — so "the text stack is fast" was only ever supported for one shape of
workload.

| mode | frame (median) | vs Latin | GPU |
|---|---|---|---|
| LATIN_BASELINE | 10.20 ms | 1.00x | 0.395 ms |
| **RTL_ARABIC** | 23.96 ms → **fixed, see below** | **2.35x** | 0.744 ms |
| **BIDI_MIXED** | 17.77 ms | **1.74x** | 0.862 ms |
| FALLBACK_CHAIN | 12.01 ms | 1.18x | 0.301 ms |
| SYNTHETIC_STYLE | 12.46 ms | 1.22x | 0.333 ms |
| DECORATIONS | 12.57 ms | 1.23x | 0.296 ms |

GPU stays under 1 ms in every mode — CPU-bound throughout, consistent with §1.

Fallback chains, synthetic bold/italic and decorations all land within ~25% of plain Latin. None of
them is a problem, and that is now measured rather than assumed.

### FIXED — RTL line breaking was re-shaping instead of slicing

`CgLineBreaker.safeGlyphBoundary` began with `if (run.rtl()) return -1;`, so **every** RTL break
candidate fell back to a full HarfBuzz re-shape of both halves. That made `wrap.breakLines` 13.2 ms
against Latin's 0.64 ms — twenty times worse, and the single largest cost anywhere in the text stack.

The bail was conservative rather than wrong: HarfBuzz emits RTL glyphs in *visual* order, so the
logical prefix is the upper part of the glyph array and cluster ids descend as the index rises. Three
things had to mirror, and getting any of them wrong corrupts text silently rather than loudly:

1. **Slice ranges mirror** — head is `[splitGlyph, count)` and tail `[0, splitGlyph)`, the reverse
   of LTR.
2. **Cluster rebase uses the minimum cluster in the slice**, which for RTL is at `glyphTo - 1`, not
   `glyphFrom`. Using `glyphFrom` leaves every RTL tail with negative cluster ids.
3. **The unsafe-to-break flag is read at `splitGlyph - 1`.** HarfBuzz's flag belongs to the glyph
   whose cluster *starts* at the break offset; under RTL that glyph is on the suffix side. Reading
   it at `splitGlyph` tests the wrong boundary — this was caught by the equivalence test diverging
   by exactly one glyph's advance (23.95 vs 23.16), not by inspection.

Proven by extending `CgLineBreakerSliceEquivalenceTest` with Arabic and bidirectional strings, which
wraps identical text both ways and compares every glyph: **36 LTR + 24 RTL/BiDi combinations
identical.**

| metric | before | after | change |
|---|---|---|---|
| RTL frame | 31.55 ms | 24.43 ms | **-23%** |
| RTL reshape | 24.27 ms | 17.61 ms | -27% |
| `wrap.breakLines` | 13.25 ms | **7.32 ms** | **-45%** |
| RTL `hb.shape` | 13.02 ms | 8.79 ms | -32% |

Still ~10x Latin rather than 20x. The remainder is inherent: Arabic is cursive and contextual, so
HarfBuzz genuinely marks far more boundaries unsafe-to-break than Latin does, and those must
re-shape. Closing the rest would mean changing what is shaped, not how it is sliced.

### Ranked item 1 — `resolveGlyphs` residue: explained, not worth fixing

`resolveGlyphs` 2.50 ms = `placementCache.lookup` 0.14 + `flatten` 0.54 + `resolvePlacements` 0.99 +
**`placementCache.put` 0.36** + ~0.47 residue.

`placementCache.put` is four `Arrays.copyOf` per draw — 4000 array allocations/frame at 1000 labels.
It is only paid on a cache *miss*, and on a miss those copies are what makes the next frame a hit,
so it is not waste. In the realistic STATIC case the cache hits 1000/1000 and this cost is never
reached at all. Not fixed deliberately.

Of the remaining residue, a large part is **the profiler itself**: STATIC shows 0.254 ms of residue
while doing essentially no work (pure cache hit), which at ~1000 calls/frame is ~250 ns per scope.
Sub-instrumenting further would mostly measure the measurement. Treat ~0.25 ms/frame as the floor
for any scope called once per label.

### Ranked items 2–6 — disposition

| # | item | disposition |
|---|---|---|
| 2 | `hb.shape` 2.78 ms | **Native leaf, not micro-fixable.** The RTL fix cut it 32% for Arabic by shaping less, which is the only lever that works: shape *fewer times*, don't make the call faster. The structural answer is the caching work below. |
| 3 | shaping gaps ~1.5 ms | **Largely the profiler.** `shape.runs` -> `collectRuns` is 0.27 ms over 1000 labels = ~270 ns/label, the same per-scope overhead measured in item 1. Not real work; sub-instrumenting further would measure the measurement. |
| 4 | `quadLoop` 1.13 ms | **At the floor.** ~20 ns/quad for 17,000 quads once nested `doBind` is excluded. Nothing to take. |
| 5 | `bufFill` -> UTF-16, 0.377 ms | **Deliberately not attempted.** It would change cluster ids from UTF-8 byte offsets to UTF-16 code-unit offsets, which every glyph-to-source mapping depends on — `CgLineBreaker`'s rebasing, `Utf8ClusterMapper`, the slice fast path. Surrogate pairs make it worse than a rename. 0.377 ms does not justify that blast radius; revisit only if it is ever paired with removing `Utf8ClusterMapper` outright. |
| 6 | CrystalGUI `UIText` +2.8 us/label | **Out of scope by prior direction** ("solely improve the main text layout engine backend"). Also note UIText is *faster* than the pure path on SAME_LENGTH because it skips reshaping unchanged text — the caching win, already visible. |

### Next leads (measured, not yet acted on)

- **BiDi pays 5.08 ms of per-run HarfBuzz buffer overhead** (`shape.harfbuzz` minus `hb.shape`),
  against Latin's 1.25 ms — 4x. Bidirectional text splits into many more runs and each pays buffer
  setup/fill/readback. That gap is now *larger than the shaping it wraps* (6.18 ms). Pooling or
  batching per-run buffers is the obvious next move for BiDi.
- **RTL still re-shapes during wrapping.** `hb.shape` (8.79 ms) exceeds its own parent
  `shape.harfbuzz` (6.57 ms), which is only possible if HarfBuzz is called from outside shaping —
  i.e. the line breaker's remaining reshape fallback. Consistent with `wrap.breakLines` still at
  7.32 ms. Confirms the residual RTL cost is unsafe-to-break boundaries, as expected.

---

## MEASURED 2026-07-29 — the "never measured" sections §1–§5

### §1 + §5 — GPU time and `text.shader`: **0.319 ms/frame**

GPU timing now exists. `CgGpuProfiler` (core) issues `GL_TIME_ELAPSED` queries through new optional
`CgGLBackend` methods; results are polled non-blocking and collected some frames later, because
reading a query in the frame it was issued stalls the pipeline and destroys the measurement.
Implemented in the harness backend; the other two backends inherit an honest "unsupported" default
rather than an abstract method, since `ARB_timer_query` is not guaranteed on GL 2.1-era MC 1.7.10
contexts.

Measured on text-stress, 1000 labels / ~17,000 glyph instances, wrapping the entire text draw:

```
gpu.textDraw   0.319 ms/frame over 745 samples
```

**Against ~5.4 ms of CPU draw, text rendering is ~6% GPU-bound.** Fill rate, the MSDF fragment
shader, and texture bandwidth are all inside that 0.319 ms. There was no hidden cost here — §5 is
answered by the same number and needs no separate investigation.

### §2 — Bitmap / FreeType raster path: 0.080 ms/glyph, spikes to 9.4 ms

From text-3d warmup (sampled frames):

| scope | total | self | calls | max |
|---|---|---|---|---|
| `freetype.rasterize` | 387.07 ms | 247.32 ms | 4810 | **9.425 ms** |

**~80 us per glyph**, an order of magnitude cheaper than MSDF generation's 13 ms — which is why the
bitmap fallback is the right thing to show while MSDF generates. The 9.4 ms max on a single
rasterize is worth a look if first-frame hitching ever matters.

### §3 — Atlas growth and upload: effectively free

| scope | total | calls | max |
|---|---|---|---|
| `atlas.growCapacity` | 1.04 ms | 3 | 0.708 ms |
| `texArray.growGpuCopy` | 0.43 ms | 3 | 0.281 ms |
| `texArray.upload.texSubImage` | 0.50 ms | 31 | 0.165 ms |

**~2 ms total across an entire CJK warmup.** Layers grew 1→2→3→5. Nothing to optimise; treat as
closed.

### §3c — Font loading: **130 ms, and it is all in frame 1**

| scope | total | calls | max | avg bytes |
|---|---|---|---|---|
| `font.loadNative` | **130.18 ms** | 8 | **126.549 ms** | 811 KB |

One load accounts for 126.5 of the 130 ms; the other seven total ~3.6 ms (~0.5 ms/font). Since the
first call is also what triggers `NativeLoader.ensureLoaded()`, that 126.5 ms is most likely the JNI
library load being charged to the first font rather than font parsing itself — worth confirming
before treating it as a font cost.

**This is a genuine ~130 ms startup stall**, not a measurement artifact. I initially suspected it was
the same first-frame stall as `glFlush`/`material.doBind` (both ~134 ms), but those land in **frame
2** while this lands in **frame 1** — different frames, different causes. Frame 2's ~134 ms is shader
compilation (`material.getOrCompileVariant`), which is a separate startup cost worth its own note.

### §3b — `CgTextLayoutCache`: works, but no benchmark exercises it

Instrumented (`layoutCache.hit` / `.miss` / `.size`). Across the whole text-3d run: **2 hits, 2
misses, size 2.**

Both stress scenes call `.layout(prebuiltLayout)` and never `.text(String)`, so they bypass this
cache entirely — which also means the 7.9 ms reshape figure they report is a *worst case that the
cache would absorb in real UI*. **Its real-world hit rate remains unmeasured**, and it should be
measured before the dirty-flag caching work is designed, since the two overlap.

### §4 — DONE: benchmark built, one defect found and fixed

Superseded — see "§4 BUILT AND MEASURED" above. `text-feature-stress` now covers RTL/BiDi, fallback
chains, synthetic bold/italic and decorations. It found RTL line breaking re-shaping instead of
slicing, which is fixed.

---

## Never measured at all

This is the real gap. Everything above is CPU-side; the items below have no numbers whatsoever.

Derived by auditing profiler coverage per package rather than from memory — reproduce with:

```bash
grep -rhoE 'CgProfiler\.(scope|count|sample)\("[^"]+"' text/ gl/ api/ | sed -E 's/.*\("//' | sort -u
```

**Packages with zero profiler scopes: `api/text` (13 files), `api/font` (12 files),
`gl/render` (9 files).**

### 0. RESOLVED 2026-07-29 — was a harness name mismatch, not missing instrumentation

See the measured breakdown above. `submitSortedQuads` was always scoped; the CSV read
`submitBatchedQuads`. Real value 1.82–2.03 ms. `gl/render` is now instrumented too
(`quadRenderer.*`, `batch.*`), and the GPU tail turned out to be ~0.27 ms with a single draw call.

*Original text kept below for context.*

### 0-original. ~1.9 ms of `draw` is attributed to nothing — and `gl/render` is uninstrumented

`draw` is 5.77 ms. Its measured children are `resolveGlyphs` 2.70 and `quadLoop` 1.17 — and
`matDoBind` 0.43 / `dbStateSave` 0.39 are *nested inside* `quadLoop` (material transitions fire from
within the quad loop), not siblings of it. That leaves roughly **1.9 ms of `draw`, about a third,
unaccounted by any scope**.

The most likely home is `gl/render` — `CgBatchRenderer`'s vertex writing, staging buffer, VBO upload
and draw call — which has **no instrumentation at all**. `quadLoop` measures the loop that *submits*
quads; everything between submission and the GPU is dark.

**This is the cheapest high-value thing on this list**: it is a known-size hole (~1.9 ms of a
13.9 ms frame) in code that currently cannot be seen into.

### 1. GPU time — nothing, anywhere

**There are zero timer queries in the entire codebase** (no `glBeginQuery`, `GL_TIME_ELAPSED`,
`glQueryCounter`). Every performance number produced for the text stack is CPU-side. 20,000 textured
quads through an MSDF fragment shader is not obviously cheap, and if any part of the frame is
GPU-bound it is currently invisible. `glFlushMs` is a proxy and a misleading one.

A real cost could be hiding here completely unmeasured, whereas the CPU items below are merely
unoptimised. Second only to §0 in value, and for the same reason: both are blind spots rather than
known-but-slow code.

### 2. Bitmap / FreeType raster path

`freetype.rasterize` has only ever been seen incidentally in warmup traces, never profiled
deliberately. It renders *every* glyph before its MSDF lands, so it is on the critical path for the
first seconds of any session.

### 3. Atlas growth and upload

`atlas.growCapacity`, `texArray.growGpuCopy` appear in traces; never drilled into.

### 3b. `api/text` — the public layout API, zero scopes (13 files)

`CgTextLayout`, `CgShapedRun`, `CgBakedGlyphs`, `CgTextLayoutCache`. Nothing here is measured.

Most notable: **`CgTextLayoutCache`'s hit rate is unknown.** That is directly load-bearing for the
dirty-flag caching work below — if the layout cache is already absorbing repeats, the reshape
numbers above are not what a real screen would see, and the caching design should be informed by its
actual hit rate rather than assumed to be starting from zero.

### 3c. `api/font` — font loading and fallback, zero scopes (12 files)

`CgFont`, `CgFontFamily`, font loading, glyph index lookup, the fallback chain. The cmap coverage
cost (1.19 -> 0.43 us) was measured with a scratch test, never in-situ. Font loading and fallback
resolution have never been measured at all.

### 4. Paths the benchmarks do not exercise

Neither scene covers these, so "the text stack is fast" is not supported for them:

- BiDi / RTL under real load
- font fallback chains
- synthetic bold / italic
- decorations (underline, strikethrough)
- mixed fonts within a single run

### 5. `text.shader` itself

Never measured. Follows from (1).

---

## Known CPU work, quantified, still open

Ranked by size. None of these are urgent — at 13.9 us/label in the pathological case the CPU side is
past diminishing returns.

**These are `text-stress` numbers** — 1000 labels reshaped *and* re-laid-out every single frame.
That is deliberately pathological and it matters for how you read row 1: text-stress defeats every
cache by construction, so it measures the cost of doing the work, not the cost a real UI pays.
Re-measured 2026-07-29 after the placement-cache fix.

| # | Item | Cost | Notes |
|---|---|---|---|
| 0 | ~~unaccounted `draw` time~~ | ~~1.9 ms~~ | **RESOLVED.** Whole-frame accounting landed; unaccounted is now 0.008 ms mean / 0.1%. See "Instrumentation added". |
| 1 | `resolveGlyphs` | **2.49 ms** (was 2.70) | **Question answered: it is cache misses.** The same scope is **0.010 ms** in `text-3d`, where layouts are stable — a 250x difference on identical code. text-stress rebuilds every layout every frame, so `CgGlyphPlacementCache` misses by design and this measures a full re-resolve. Not worth optimising against this scene; it is already ~0 whenever a layout survives a frame. |
| 2 | ~~`hbShape`~~ | **2.82 ms** | **CLOSED — this is the floor.** It is time inside native HarfBuzz doing the actual shaping; there is no CrystalGraphics code in it to optimise. The only lever is *avoiding the call*, which `CgTextLayoutCache` already does. Do not reopen. |
| 3 | ~~Unattributed shaping gaps~~ | ~1.06 ms | **CLOSED — measured, and it is mostly the profiler.** 0.259 ms is real (array allocation + unpack + run construction); the rest is ~250 ns x ~7 scopes x ~1000 shape calls/frame. Scopes added to find it were removed again because they cost more than they revealed. Do not reopen without a workload that shapes far less often than text-stress. |
| 4 | `quadLoop` | **1.11 ms** | **Confirmed at the floor.** 0.136 us/quad, and per-iteration tracing shows no single slow iteration on any spike. Do not revisit — see "Not on this list". |
| 5 | ~~`bufFill` -> `hb_buffer_add_utf16`~~ | 0.33 ms | **CLOSED — decided to stay on UTF-8.** Migrating cluster indices to UTF-16 code units would touch `CgLineBreaker`'s cluster rebasing throughout for 0.33 ms. Not worth the correctness surface. Do not reopen. |
| 6 | CrystalGUI `UIText` layer | ~1.3 us/label | **Deferred to CrystalGUI's own profiling phase**, not text-stack work. |

---

## Cache hygiene — the only survivors of the deleted rewrite plans

`docs_research/TEXT_ENGINE_REWRITE_MASTER_PLAN.md` and
`docs_research/text_layout_dod_rewrite_plan.md` were deleted on 2026-07-29 after a
phase-by-phase audit found all 13 phases implemented (see "Text engine rewrite: complete"
below). These two items are the **only** things in either document that had not landed. Both
came from the DOD plan §10, which checked our caches against their real production analogues
rather than against intuition — that provenance is why they are worth keeping.

### 1. Neither cache has a byte budget — only an entry count

`CgGlyphPlacementCache` and `CgTextLayoutCache` both evict purely on entry count via
`LinkedHashMap.removeEldestEntry`. Skia's `SkStrikeCache` — the actual production analogue —
uses a **dual budget**: 2 MB *and* 2048 entries, evicting LRU against whichever is hit first.

Why it matters here specifically: a `CgGlyphPlacementCache.Entry` holds `glyphX`, `glyphY`,
`argbColor` and `placements` arrays, so its real cost scales with that layout's glyph count —
which varies by **orders of magnitude** between entries. A 2-glyph button and a 3363-glyph
paragraph (a real case, measured in `text-3d`) count identically toward the cap, at roughly
20 bytes/glyph — so the same 1024-entry cache is a few hundred KB of buttons or ~69 MB of
paragraphs. The cap does not bound memory in any meaningful sense.

Fix: rough byte accounting alongside the existing map, evicting once either budget is exceeded.

### 2. `CgTextLayoutCache` is not invalidated by font hot-reload

Its own javadoc already admits this: a cached `CgTextLayout` keeps referencing pre-reload glyph
ids and metrics after F3+T until it is evicted normally. Blink's `FrameShapeCache` has an
explicit frame-generation invalidation hook (`DidSwitchFrame`) for exactly this, which makes
this a precedented fix rather than a hypothetical one.

Low priority while no Minecraft module is in the build (nothing triggers a resource reload), but
it is a correctness bug, not a performance one — worth doing before any loader module lands.

Also noted, not recommended: `CgTextLayoutCache`'s `CAPACITY = 512` has no stated derivation
(Blink's equivalent holds 32,768). Different workload scale, so not damning on its own — but it
is the same undocumented-magic-constant pattern that `DIAGNOSIS.md` C3 already flagged and fixed
once for `MAX_COMMITS_PER_FRAME`.

---

## Feature gaps: plumbed but not connected (from the deleted layout-creation doc)

`docs_research/CGTEXT_LAYOUT_CREATION_API.md` was deleted on 2026-07-29. Its §1/§2/§4/§5/§6 all
shipped — the paragraph knobs (`align`, `maxLines`, `ellipsis`, `direction`, `lineHeightOverride`,
`tabStopWidth`), the builder, and both seams. Its **§3** (per-span knobs cross-checked against
Skia's `TextStyle`) did not fully land, and these three are the residue.

All three share one failure shape, the same one that produced the `markEmpty` bug: **the data is
threaded faithfully through every layer and then dropped at the last step.** They do not throw,
they do not warn — a caller sets one and nothing happens. Warnings are on `CgStyleSpan`'s javadoc
so this is visible at the point of use, not only here.

| Field | State |
|---|---|
| ~~`fontFeatures`~~ | **WIRED 2026-07-29.** Threaded span → `collectShapedRuns` → `CgTextShaper` → `HBShape.shape(font, buffer, String[])`. `CgFontFeature(tag, value)` maps to HarfBuzz's own `"tag=value"` syntax; the JNI layer already ran each string through `hb_feature_from_string` and skipped rejects. Verified by shaping `"AVAVAVAV"` with `kern=0` and asserting the advances differ. |
| ~~`baselineShift`~~ | **WIRED 2026-07-29.** One term at bake: `penY[i] = lineBaseline + offsetsY[i] + run.baselineShift()`. Negative moves up the screen. Deliberately does **not** grow the line box — a shifted span may overhang, matching CSS `vertical-align`'s default; growing the line would relayout every line merely containing a shifted span, which is a far larger behavioural change. |
| ~~`fontFamilyOverride`~~ | **DELETED 2026-07-29**, not implemented. Consuming it needed per-span family resolution ahead of run splitting, tangled with `CgFontFamilyGroup` and the fallback chain, and nothing was asking for it. A public API that advertises a capability it does not have is worse than one that does not offer it. Per-span family selection remains available via `bold`/`italic` + `CgFontFamilyGroup`, and per-*codepoint* fallback is what `CgFontFamily.resolveRuns` already does. Breaking change to the `CgStyleSpan` record — reinstate only alongside a real implementation. |

Tests: `CgSpanEffectTest`. They exist because the failure mode is invisible to every other test in
the suite — the layout stayed well-formed, just not the layout that was asked for.

**Gotcha found while writing them:** any span splits the shaping run at its boundaries, and
separately-shaped runs lose cross-boundary kerning, so *adding a span at all* changes a string's
measured width slightly. A test comparing spanned against unspanned text fails for reasons unrelated
to what the span contains; compare against a span with the same boundaries and a neutral value.

Deliberately still not built, and correctly so — non-solid decoration styles (dashed/dotted/wavy)
are shader work, and shadows, gradient/paint fills and per-span locale-aware shaping were all
explicitly deferred with reasons.

---

## Text engine rewrite: complete

Audited phase by phase against the code on 2026-07-29. All 13 phases of the master plan are
implemented, so both plan documents were deleted:

| Phase | Landed as |
|---|---|
| 1 Reshape context | `text/layout/CgReshapeContext` |
| 2 `CgShapedRun` storage | `sourceText` removed, `resolvedFont` added, nullable offsets |
| 3 Baked glyphs + Seam A | `api/text/CgBakedGlyphs` with `justifiable`/`lineStart`/`lineHeight` |
| 4 `CgResolvedGlyphs` | `flatten` reads the baked structure |
| 5 Test updates | suite green |
| 6 Rich-text IR | `CgStyledText`, `CgStyleSpan`, `CgFontFamilyGroup`, `CgFontFeature`, `CgTextDecoration` |
| 7 Markup parsers | `text/richtext/CgMarkupParser` (+ HTML and Minecraft implementations) |
| 8–9 Rich spans, BiDi∩span split | per-run span fields; harness `cgui`/`text-feature-stress` modes cover it |
| 10 Reshaper vs. family group | resolves the styled face |
| 11 Per-glyph colour | `CgBakedGlyphs.argbColor`; `CgGlyphPlacementCache.Key` carries `rgba` |
| 12 Layout request API | `CgTextLayout.Request` (nested, not the planned standalone `CgTextLayoutRequest`) — same knobs: `maxLines`, `ellipsis`, `align`, `direction`, `lineHeightOverride`, `tabStopWidth`, `markup` |
| 13 Unsafe-to-break fast path | `CgShapedRun.safeToBreakBefore` + `CgLineBreaker.measurePrefixWidth`; measured 689 fast-path vs. 57 reshapes on a wrapping fixture |

The plans' own "deliberately not included" list still stands and is still not built:
justification/hyphenation (Seam A is reserved but unconsumed), block-level markup, text shadows,
gradient/paint fills, per-span locale-aware shaping.

---

## Structural work (bigger than any of the above)

### ~~Dirty-flag `CgTextLayout` so unchanged labels skip reshape entirely~~ — ALREADY IMPLEMENTED

**Do not build this.** See "RESOLVED — the structural dirty-flag caching work is already
implemented" above: `UIText.ensureShaped()` plus `CgShapedParagraph.layout()`'s memo already do it,
and STATIC measures ~zero layout calls. Original text kept below for context only.

Design notes already exist in `docs/UITEXT_CACHING_DESIGN.md` (repo root, currently uncommitted).

This does not shave the 7.94 ms reshape — it **deletes** it for any label that did not change. The
stress scene changes every label every frame, which is the pathological case; a real screen has
nearly all labels static. Strictly larger than every CPU item in the table above, and it sidesteps
`hbShape` rather than trying to optimise it.

### Persistent MSDF atlas cache (optional, low priority)

Deliberately **not** build-time baking of whole fonts — the glyph set is unpredictable, and a CJK
font is 20k+ glyphs of which a session uses a fraction. Instead: keep generation lazy and
demand-driven exactly as today, but persist the resulting atlas pages so run 2+ skips generation.
Cache size is bounded by observed usage, not font inventory — measured at **24 MB** (6 pages x 1024²
RGBA8) for the heaviest case, the full CJK scene.

Payoff is fidelity latency only (~9 s of bitmap-fallback text at startup), **not frame time**. Needs
invalidation on font-bytes hash + config hash, deterministic page layout, and a size cap with LRU
eviction. Judged not worth building unless repeat-launch fidelity becomes a priority.

---

## Closed — do not reopen without new evidence

These were investigated and measured this session. The conclusions are evidence-backed; reopening
them needs a reason beyond intuition.

### MSDF generation is not a performance problem

`msdfGenMs` is **0.00 ms in both warmup and settled frames**. Generation is fully asynchronous; it
never runs on the render thread. Its speed governs how long text renders as a bitmap fallback
(~9 s in text-3d), nothing else.

Per-glyph cost is ~13 ms and **does not measurably improve** — run-to-run variance across three
identical runs spanned 11.45 / 12.37 / 12.98 ms/glyph, larger than any effect produced. 99.3% of it
is inside a single native `generateMtsdf` call; Java-side staging is 0.7% combined.

Cost model, if anyone wants to attack it: correlates with `boxArea × edgeCount` at **0.961**, at
58–67 ns per pixel×edge (area alone only 0.73). See `CgMsdfCostModelTest`.

### Overlap support cannot be disabled — only gated

Comparing generated fields pixel-for-pixel: bit-identical on all sampled Latin glyphs, and identical
on all but **one CJK glyph in 120**, where it differs by a full sign inversion (that glyph renders as
garbage). Defect-counting was too weak a test to catch this — a 40-glyph sample missed it entirely.

`CgMsdfGenerator.needsOverlapSupport` gates it conservatively via contour bounds. Verified at
0.000000 max delta across 800 glyphs (`CgMsdfOverlapSupportEquivalenceTest`). Worth only ~1.02x on
CJK, because the glyphs it can skip are the cheap ones.

### `pxRange` padding is not worth reclaiming

Range expansion is 17.5% (CJK) / 23.0% (Latin) of box area, but area correlates only 0.73 with time,
so the ceiling is well under 20% — and `pxRange` is quality-locked (it is the constant the 64px
regressions traced to). Bad trade.

### Spatial indexing inside msdfgen — correct diagnosis, wrong target

A grid/BVH over shape edges would give a real 4–9x (it would cut ~46 candidate edges per pixel to
~5–10). But it optimises something that already costs 0.00 ms of frame time, and it means modifying
vendored upstream C++ and cross-compiling to 5 targets via Zig. Complication if ever attempted: MSDF
needs the nearest edge *per colour channel*, so distance pruning must be maintained three ways.

Also noted: `generateDistanceField` has `#ifdef MSDFGEN_USE_OPENMP` around its row loop and **we
compile without it**, and the API takes `BitmapSection`, so bands could be generated from our own
JNI layer with no OpenMP dependency. Neither reduces total CPU — only per-glyph latency — and we
already saturate cores across glyphs.

### Do not scale the glyph worker pool with core count

`CgGlyphGenerationExecutor.DEFAULT_WORKER_COUNT` is capped at 4 deliberately. Raising it to
`cores - 2` (6 on 8 cores) shortened convergence only 9.3 s → 8.4 s, with no frame-time effect
(warmup means 16.9 / 19.0 / 19.2 ms across runs, unrelated to worker count).

The workers were never the constraint: `pendingAsync` drains to zero by 2 s and sits empty for most
of the run. Glyph demand arrives progressively as the scene reveals text, so convergence is mostly
governed by *when glyphs are first drawn*. **Check `pendingAsync` in the text-3d profile before
assuming otherwise** — if it is not pinned near `MAX_PENDING_TASKS`, more workers cannot help.

Raising the commit budget would not help either: it admits ~12 glyphs/frame (1 MB at ~82 KB per
MTSDF glyph), ~2.6 s for 2000 glyphs, and is not binding.

---

## Fixed this session (context for the numbers above)

- **Material transitions 1000 → 1 per frame.** `CgTextRenderer` rebound its material on every
  `draw()` because the batch-state tracking was function-local. `doBind` 93.6 → 0.50 ms,
  `stateSave` 90.7 → 0.47 ms. Tracking is now `static` — per-instance would be unsound, since two
  renderers with interleaved batches would each assume the shared material still held what they set.
- **Sync generation frame stall, 128 ms → 33 ms.** The per-frame limit was a *count* of 4 glyphs,
  which only prevents spikes if glyphs cost the same (they range <1 ms to 32 ms). Now a 2 ms time
  budget, checked before generating and charged after, so the first glyph of a frame always proceeds
  (progress guaranteed) but one expensive glyph exhausts the frame.
- **Intermittent JVM crash — reachability race, previously misdiagnosed.** Wrappers hold a native
  pointer and free it from `finalize()`; once the pointer is read into a call argument the wrapper is
  unreachable *while the native call is still running*. Fenced via `NativeReachability` across
  `MSDFGenerator`, `HBBuffer`, `HBShape`. Was blamed on msdfgen's error correction; that explanation
  was wrong and is corrected in `CgMsdfGenerator`'s javadoc. See the bindings `AGENTS.md`.
- Wrapping `wrap.breakLines` 5.36 → 0.74 ms, glyph readback 8.58 → 0.16 us, cmap coverage
  1.19 → 0.43 us, packing 1129 → 99 ms.

### Later the same day — the async bitmap fallback landed, and one real bug behind it

- **Bitmap fallback is now asynchronous.** The two blockers recorded in the earlier
  "attempted and reverted" write-up were fixed rather than worked around: `CgResolvedGlyphs` now
  refuses to cache a placement array containing a `null` (`hasDeferredGlyphs`), and
  `MAX_PENDING_TASKS` went 256 → 16,384 with `hasFailed`/`submit` letting a caller distinguish
  "queue full, retry" from "failed, fall back". Render-thread `freetype.rasterize`
  **387 ms / 4810 calls → ~10 ms / 13 calls**; full bitmap coverage in **~1 s**.
- **Empty glyphs were dropped by the async commit — a single space blocked two whole layouts from
  ever caching.** `commitGeneratedGlyph`'s bitmap branch returned without calling `markEmpty()`,
  and a missing atlas entry is indistinguishable from "not generated yet", so the glyph was
  re-requested forever and `resolveGlyph` returned `null` forever. That `null` then poisoned the
  entire placement array. **This was a regression from making the fallback async** — the synchronous
  path always marked empty, with a comment stating exactly this failure mode, so the correct
  behaviour simply stopped being the one that ran.
  Steady state: `queried` **3406 → 45 glyphs/frame** (now exactly equal to glyphs drawn),
  `resolvePlacements` **1.68 ms → 0.000 ms**, `scene` **5.10 → 1.79 ms**.
- **`measurePrefixWidth` rebuilt an O(run-length) substring + UTF-8 map per binary-search step** —
  ~480 needless 3363-char copies per layout. Hoisted to once per search (−14% on `breakLines`).
- **Harness scene bug, not engine:** `TextScene3D` called `getArialPrintableChars()` twice per frame
  *inside the profiled draw scope*. It scans all 65,504 BMP codepoints through
  `java.awt.Font.canDisplay` — 59 ms cold, 3.2 ms warm, per call — and returns a 3363-char string.
  That is where `queried = 3406` (3363 + 43 kanji) came from, and ~62 ms of frame 1. Hoisted to a
  static field; frame 1 **146 → 107 ms**.
- **CrystalGUI:** rotated, sheared and out-of-plane transforms now force the distance-field tier at
  any size (`OrthographicScaleResolver.isAxisAligned`); quarter-turns and flips stay on bitmap since
  they are texel-exact. Verified 1000/1000 forced for rotated/sheared, 0 for quarter-turn.
- **Viewport culling.** `CgViewFrustum` + `CgTextCuller`; camera rotated away = 8107 → 42 quads and
  2.802 → 0.043 ms scene time. See "Resolved from the previous list".
- **`sliceRun` 4.53 → 1.49 ms (3.0x)** via `Arrays.copyOfRange`. The 9.9x copy amplification is
  untouched — see actionable item 3 for why the remaining ~1.3 ms was left.
- **Worker threads are now in the profile dumps** (`CgProfilerDump.dumpAllThreads`), so
  `worker.rasterizeBitmap` and the MSDF worker scopes are finally visible.
- **New CSV columns / flags:** `drawsCulled`, `cullTested`, `cullRejected`, `quadsEmitted`,
  `texSubImageMs`/`Calls`, the `lb*` line-break breakdown, plus
  `-Dcrystalgraphics.text.traceQuadLoop=true` and `-Dcrystalgraphics.text.msdfWorkers=N`.

---

## Test suite: green

`CgVertexAttributeInjectionTest`'s two long-standing failures are fixed. They were **stale tests, not
broken code**: commit `328a617` renamed the `pos2`/`pos3` format attributes from `a_pos`/`a_uv`/
`a_color` to `cg_Position`/`cg_TexCoord0`/`cg_Color`, aligning them with the `cg_*` aliases
`cg_env.glsl` declares, and the assertions still looked for the old names. Updated to the current
names; the vec2-vs-vec3 distinction the tests exist to check is unaffected, since component count is
what separates the formats now that they share attribute names.

Full `:core:test` run is clean.

## Harness notes

- `TextScene3D.dumpMsdfGenerationProfile()` now also runs on scene close, not only on the `[` key —
  a key binding cannot fire in an automated run, which is exactly when the number is wanted.
- `TextScene3D.consumeKeyboardEvent` has a missing-braces bug: `dumpMsdfGenerationProfile()` runs on
  *every* keyboard event, not just `[`. Left as-is; one-line fix.
