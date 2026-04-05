# Atlas Review — Post-Overhaul Output vs Plan vs MSDF-Atlas-Gen Reference

## Scope

This review compares the current generated atlas outputs under:

- `gl-debug-harness/harness-output/atlas/`

against:

- the approved implementation plan at `.sisyphus/plans/atlas-generation-overhaul-msdf-parity.md`
- the harness validation doc at `docs/font/atlas-dump-harness.md`
- the reference image `gl-debug-harness/resources/MSDF-Gen-Atlas atlas.png`
- the pre-overhaul baseline `gl-debug-harness/harness-output/atlas_pre_overhaul/msdf-atlas-dump-128px.png`

Files reviewed:

- `gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px.png`
- `gl-debug-harness/harness-output/atlas/msdf-atlas-dump-128px-manifest.txt`
- `gl-debug-harness/harness-output/atlas/bitmap-atlas-dump-128px.png`
- `gl-debug-harness/resources/MSDF-Gen-Atlas atlas.png`
- `gl-debug-harness/harness-output/atlas_pre_overhaul/msdf-atlas-dump-128px.png`

---

## Short answer

### Is it proper?

**Functionally: yes.**

The new output proves the new paged atlas pipeline is live, the harness is reading the live paged path, and the MSDF atlas is being generated and dumped successfully.

**Parity-wise: not yet.**

It is clearly an improvement over the pre-overhaul atlas, but it still does **not** match the packing character of `msdf-atlas-gen` closely enough to call it true parity.

### Did we get the packing correct?

**Partially.**

- We got a working, stable, deterministic packed atlas.
- We did **not** get `msdf-atlas-gen`-level compactness.
- The current output still looks much closer to a runtime row/shelf style layout on an oversized page than to a tight offline geometry-first atlas.

### Are there parity gaps in the plan or packing?

**Packing: yes, still significant.**

**Plan: mostly no.** The plan correctly anticipated that parity required upstream-style glyph box math and tight packing semantics. The current review suggests the implementation has not fully realized that goal in the produced image yet.

### What about the dimensions difference: our `128px -> 2048x2048` vs reference `800x250`?

This is the strongest evidence that parity is still missing.

- Reference image dimensions observed: about **800×250**
- Current generated MSDF dump: **2048×2048**
- Current manifest utilization: **12.5%**

Even accounting for unknown reference font size / charset / range settings, this gap is too large to dismiss as a simple configuration difference. It strongly suggests we are still over-allocating the atlas and/or not packing tightly enough.

### Overall feeling

**Better, but not there yet.**

The overhaul succeeded at architecture and tooling. The output quality improved from the obviously broken pre-overhaul case. But visually and dimensionally, the generated atlas still does **not** feel like an `msdf-atlas-gen` atlas.

---

## What the current generated output proves

The following is now working correctly:

1. The harness runs successfully and dumps the atlas from the **paged** path.
2. The MSDF dump manifest is generated.
3. The atlas contains the expected printable ASCII glyph set.
4. Prewarm is converging and populating the atlas deterministically.
5. The renderer/registry/paged atlas integration is real, not dead scaffolding.

Evidence from `msdf-atlas-dump-128px-manifest.txt`:

- page count: `1`
- dimensions: `2048x2048`
- glyph count: `94`
- packed area: `522207 px`
- utilization: `12.5%`

That is enough to say the overhaul is alive and routed correctly.

---

## Visual comparison

## 1. Reference atlas (`MSDF-Gen-Atlas atlas.png`)

Observed characteristics:

- approximately **800×250**
- very dense horizontal strip layout
- minimal dead space
- multiple compact uneven rows spanning the full width
- clearly geometry-aware packing
- broad glyph size variance handled efficiently

Overall impression:

- this looks like a true offline tight atlas
- the page dimensions feel solved from content, not preallocated conservatively

## 2. Current generated MSDF atlas (`msdf-atlas-dump-128px.png`)

Observed characteristics:

- **2048×2048** page
- content concentrated mostly in the upper-left portion
- large unused empty black regions remain
- row/shelf structure is still visually obvious
- the output does not read as globally space-optimized

Overall impression:

- much better than the pre-overhaul baseline
- still sparse relative to the reference
- still looks more like a runtime-friendly atlas on an oversized canvas than an upstream-tight atlas

## 3. Pre-overhaul MSDF atlas (`atlas_pre_overhaul/msdf-atlas-dump-128px.png`)

Observed characteristics:

- extremely poor utilization
- severe left-edge / top-band concentration
- massive unused regions
- obvious fragmentation and broken-looking wrap behavior

Overall impression:

- clearly worse than the new output
- the new output is a real improvement

---

## Direct answers

## Is it proper?

### As a working atlas system

**Yes.**

The system is valid, renders, dumps, manifests, and routes through the new paged path.

### As `msdf-atlas-gen` parity

**No, not yet.**

The current atlas does not visually or dimensionally match the reference packing style closely enough.

---

## Did we get the packing correct?

### Correct in the sense of stable and valid

**Yes.**

The atlas content is ordered, deterministic, and non-broken.

### Correct in the sense of upstream parity

**Not yet.**

The packing still appears too conservative:

- oversized final page
- low utilization (`12.5%`)
- too much preserved whitespace
- visually row/shelf-biased rather than tightly compacted

---

## Parity gaps

## Gap 1 — Atlas dimensions are still far too large

The current atlas is `2048×2048`, while the reference is around `800×250`.

That does **not** automatically prove the implementation is wrong, because:

- the reference may use a different font
- the reference may use a different glyph set
- the reference may use a smaller font size
- the reference may use different range/padding settings

But combined with `12.5%` utilization, it strongly indicates the current sizing logic is still far from upstream-tight behavior.

## Gap 2 — Packing still looks row/shelf oriented

The current output visually resembles:

- left-to-right rows
- uneven row endings
- large unused rectangular regions after rows terminate

This is not how the reference reads visually. The reference looks globally packed around actual glyph boxes.

## Gap 3 — Output naming/docs mismatch exists in current artifacts

The review found that the actual `harness-output/atlas` directory currently contains:

- `msdf-atlas-dump-128px.png`
- `msdf-atlas-dump-128px-manifest.txt`
- `bitmap-atlas-dump-128px.png`

but **not** `msdf-atlas-dump-128px-page-0.png` in the currently inspected directory listing.

That means either:

1. the latest run overwrote/normalized to single-page naming, or
2. the docs/logging and output naming are not fully aligned, or
3. the inspected directory contains artifacts from different runs.

This is a tooling/documentation parity gap rather than a core packing gap, but it should be cleaned up.

## Gap 4 — Bitmap output also still looks runtime-oriented

The bitmap atlas also looks like:

- top-left shelf packing
- large dead space below and to the right

This is acceptable for a runtime cache, but it is **not** “tight offline atlas” quality.

---

## Plan review against reality

## Was the plan wrong?

**Mostly no.**

The plan correctly identified the necessary direction:

- geometry-first MSDF layout
- tighter packing semantics
- paged atlases
- deterministic prewarm and dump tooling

The current results suggest the **implementation outcome** has not fully reached the plan’s parity target yet.

## Any plan gap worth noting?

One subtle gap is that the plan focused heavily on algorithmic parity, but the review suggests we still need a more explicit **dimension-solving / atlas-size-selection parity check**.

In practice, this means the next revision should explicitly compare:

- chosen page dimensions
- total packed area
- final whitespace ratio
- row/box distribution shape

against an upstream benchmark run using the same font, charset, and approximate range settings.

That would turn “looks closer” into a measurable parity target.

---

## Why our atlas can be much larger than theirs

Several factors can legitimately widen the gap:

1. **Unknown reference font size**
   - if their reference is not 128px, direct dimensional comparison is not apples-to-apples

2. **Different glyph set**
   - reference may contain fewer glyphs or a different subset

3. **Different padding / pxRange / emRange**
   - MSDF box size changes substantially with range and padding policy

4. **Page sizing policy**
   - if our harness or renderer still preselects a large power-of-two page for runtime convenience, we will never visually match upstream’s content-fit strip behavior

5. **Packing heuristic still not equivalent**
   - if our boxes are correct but the placement heuristic is still more runtime-friendly than upstream-tight, the atlas will remain sparse

However, even after accounting for all of that, `2048×2048 @ 12.5% utilization` is too weak to call parity.

---

## Overall judgment

## What improved

- The new atlas is **definitely better** than the pre-overhaul dump.
- The catastrophic fragmentation of the old output is gone.
- The harness and paging infrastructure are in much better shape.
- The output is now reviewable and measurable.

## What is still not good enough

- The atlas is still far too sparse.
- The page dimensions are still far too generous relative to content.
- The visual structure still does not resemble `msdf-atlas-gen` closely enough.

## Final verdict

**The overhaul is a successful architectural step, but not yet a successful parity result.**

If the question is:

> “Did we build a working new atlas system?”

then the answer is **yes**.

If the question is:

> “Did we achieve msdf-atlas-gen-quality packing parity?”

then the answer is **not yet**.

---

## Recommended next actions

1. **Run a forced smaller-page MSDF dump**
   - `--atlas-page-size=512`
   - inspect whether multiple pages produce better effective compaction behavior or merely spread the same shelf strategy across pages

2. **Benchmark against a true upstream-controlled case**
   - same font
   - same glyph set
   - same px size
   - same distance range / padding
   - compare output dimensions and packed area directly

3. **Audit final atlas-size selection**
   - determine why the parity run still ends up at `2048×2048`
   - this is likely one of the biggest remaining parity blockers

4. **Audit whether the active packer is truly the intended tight path**
   - the visual output still reads more like shelf packing than upstream-tight packing

5. **Clean up artifact naming consistency**
   - docs, manifest expectations, and actual output filenames should agree exactly

---

## Bottom line

My overall feeling is:

> **This is a good infrastructure win, a clear visual improvement over the old atlas, but still not the final packing result you asked for.**

The current images are proper enough to continue from, but I would **not** sign off on “absolute msdf-atlas-gen parity” yet.
