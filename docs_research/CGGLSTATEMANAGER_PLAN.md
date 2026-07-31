# `CgGlStateManager` — Design & Implementation Plan

**Status: SHIPPED.** Two commits on `master`:

| Commit | What |
|---|---|
| `26873b6` | Replace the `glGet`-based GL state framework with a `CgGL`-level shadow (42 files, +6746/−1088) |
| `ffa6fac` | Delete the GL redirect coremod and `GLStateMirror` (16 files, −3303 net) |

**Replaced:** `GLStateMirror`, `CgGlStates`/`SlotState`, the ten per-domain state records, `CgStateGroup`,
the write-observer pair, the `cgStateWriteGuard` build task, and the entire 1.7.10 ASM coremod.
**Kept:** `CgGlState` / `CgGlScope` / `CgGlSlot` as the public API — signatures unchanged, so no call site
was edited. (`CallFamily` was *also* on this list, defended twice as load-bearing. It was not — see
below — and is now deleted too.)
**Predecessor findings:** [`GL_STATE_SYNC_STALL_FINDINGS.md`](GL_STATE_SYNC_STALL_FINDINGS.md) — the
measurement that motivates all of this. Read it first; this document does not repeat the numbers.

> **Read this if you read nothing else.** Every bug this subsystem has produced — four now — has been a
> **missing GL call**: it renders wrongly, throws nothing, and surfaces far from its cause. Unit tests pass
> through all of them. Two of the four were found only by running the harness, and one only after building a
> test double that made the restore path observable at all. Treat a green suite as necessary and nowhere
> near sufficient.

---

## V2 — move the tracker into `CgGL` (COMPLETE 2026-07-31)

### Progress — SWITCHOVER COMPLETE

| Step | State |
|---|---|
| FBO call-family design point | ✅ resolved — derivable from the bind target |
| `platform/gl/` — `CgGlStateManager`; `platform/gl/state/` — `CgGlSlot`, `CgGlStateShadow`, `CgGlScope`, `CgGlStateProvider`, `CgGlGetProvider`, `CgGlState` | ✅ written |
| Wire `CgGL`'s 28 setters to the manager | ✅ done — every setter now `if (state().xChanged(...)) backend.x(...)` |
| Delete superseded classes | ✅ **20 deleted** (see below) |
| Reduce the authoring records | ✅ `CgStateGroup` gone; `apply()` calls `CgGL` directly |
| Port `AngelicaStateProvider` | ✅ to the fill-the-shadow signature |
| Repoint imports across both repos | ✅ 18 files |
| Remove `cgStateWriteGuard` | ✅ — raw `CgGL` writes are now fully equivalent to the typed path |
| Tests for the new manager | ✅ 16, in `platform/src/test` |
| Tests | ✅ **27** in `platform/src/test`, incl. `RecordingGlBackend` (generated, 141 methods) |
| `hostForeign` boundary for Minecraft's own renderers | ✅ added |
| `PROGRAM`/`FBO` exempted from dedup | ✅ tracked but always issued |
| Package split — `platform.gl` (manager) / `platform.gl.state` (API + SPI) | ✅ done, docs follow |
| Delete the coremod + `GLStateMirror` | ✅ `ffa6fac` — see below |
| Runtime verification | ✅ `text-3d` 1120–1143 frames clean; `cgui-gallery` pixel-identical |
| Port `Blaze3DStateProvider` | ⬜ still on the old signature; `mc1201` is not in the build so it does not block |
| Re-measure `doBind` for V2 | ✅ **65.05 ms / 1.06 ms worst** — faster than V1-after |

**Green:** `:platform:test` (16) · `:core:check` (**761**, 0 failures) · `:mc1710:compileJava` + `:mc1710:test`.
CrystalGUI's *main* source compiles; its test/harness compile is broken by an unrelated in-flight refactor
in that repo (`com.crystalgui.core.input` deleted by another change, visible as a staged deletion).

**Deleted (20):** `CgStateGroup` · old `CgGlState`/`CgGlScope`/`CgGlSlot`/`CgGlStateManager`/`CgGlStateProvider`/`CgGlGetStateProvider` · `CgGlWriteObserver` + `CgGlStateWriteObserver` · `CgFboState` · `CgProgramState` · `CgTextureUnitsState` · `CgVertexInputState` · `CgColorMaskState` · `CgViewportState` · `CgScissorState` · `CgPolygonOffsetState` · `CgPolygonModeState` · `CgLineWidthState` · `CgPointSizeState` · plus the guard task and four obsolete test classes.

**Coremod + mirror: deleted** in `ffa6fac`, superseding the "kept deliberately" note that stood here. The
hotswap entanglement turned out to be narrower than feared — see *Removing the coremod* below.

### Bug found at first run: the element array buffer is not global state

`text-3d` died immediately with **"Cannot use offsets when Element Array Buffer Object is disabled"** at
`glDrawElementsInstanced`. The shadow tracked `GL_ELEMENT_ARRAY_BUFFER` as an ordinary global, but it is
**per-VAO state** — `glBindVertexArray` silently swaps it to whatever that VAO recorded, with no
`glBindBuffer` for `CgGL` to observe.

`CgQuadIndexBuffer` is *shared*, which turned a latent flaw into a certainty: mesh A records IBO name 5
into VAO 1; mesh B binds the same name 5 into VAO 2; the shadow says "already 5" and elides it; VAO 2 ends
up with **no** index buffer, and every later draw through it fails. The error surfaced at the draw call,
several binds away from the elision.

**Fix.** A VAO change sets `elementArrayBuffer` to `UNKNOWN_BINDING` (`-1`, unreachable as a GL name, and
distinct from `0` = *known unbound*), so the next bind always issues. Deduplication still applies within a
VAO, which is where the repeats actually are — bind once, draw many. Restore skips the re-issue when the
saved value is `UNKNOWN`: restoring the VAO has already reinstated its own element binding.

`GL_ARRAY_BUFFER` needs none of this — it is genuine global context state and is not captured by a VAO.
Three tests pin all three cases (VAO switch forgets, array buffer survives, a *redundant* VAO bind forgets
nothing).

> **The general lesson for any future domain:** the shadow may only dedupe values that are truly global
> context state. Anything an object binding implicitly swaps has to be invalidated when that object
> changes, or dedup silently drops a required call.

### Bug found second: a domain is written field by field, but trusted as a whole

Caught by a *test*, not a run — but only because building `RecordingGlBackend` made the restore path
observable for the first time.

`issue()` clears the unknown bit for a whole **domain**, yet a domain is written **field by field**. So the
first field re-issued marked the domain trusted, and every remaining field then compared equal to the stale
shadow and was skipped. Restoring `DEPTH` emitted `glEnable(GL_DEPTH_TEST)` and silently dropped both
`glDepthMask` and `glDepthFunc` — two thirds of the domain left on whatever the foreign code had set.

**Fix.** `reissue` suspends deduplication (`forcing`) for the duration of one *stale* domain. A domain
nobody disturbed still takes the normal path and usually emits nothing, so the fast case is untouched.

> This is the strongest argument in the document for the recording backend. The manager agreed with itself
> perfectly throughout: `isTrusted` said trusted, the shadow held the right values, and the calls never
> reached the driver. Only asserting on **what the backend received** could see it.

### `hostForeign` — the boundary for hosting Minecraft's renderers

Raised as an objection to the whole design, and it was a fair one: this engine will run `ItemRenderer`,
entity previews and block models *inside* its own passes — foreign GL writes are a designed, frequent
feature, not a rare accident. Frame-boundary invalidation is useless against code running deep inside a
pass.

`CgGlState.hostForeign(slots…)` is a scope that differs from `save()` in exactly one way: **on exit it drops
all trust before restoring**, so declared domains are re-asserted for real rather than deduplicated away
against a shadow known to be lying. Entry is free — no `glGet`, since our shadow is still truthful going in.

> **It fixes our half only.** Minecraft keeps its own shadow, and every write we make through `CgGL` is
> equally invisible to *it*. Two shadows, each blind to the other. Before calling in, state MC cares about
> must be set through **MC's** API. On 1.7.10 + Angelica the problem largely dissolves, because our provider
> reads Angelica's mirror, which observed both sides.

### `PROGRAM` and `FBO`: tracked, never deduplicated

`hostForeign` covers boundaries we *know* about. This covers the ones we forget to mark.

Deduplication is a bet that nothing wrote GL behind our back, and the stake differs sharply by domain:

| | redundant call costs | wrong value costs |
|---|---|---|
| `FBO` | a handful of binds/frame — nothing measurable | draws into the wrong target, often **no visible output at all** |
| `PROGRAM` | `glUseProgram` is cheap, never synchronises | wrong shader; MC, Iris and every shader mod rebind constantly |

`TEXTURES` and `VERTEX_INPUT` keep dedup — high call volume justifies the narrower risk.

> **The trade is far cheaper than it looks, and this is the key realisation:** the headline win came from
> removing `glGet` **synchronisation in scope capture**, *not* from eliminating writes. Write dedup is a
> second-order gain layered on top, so exempting a domain costs almost none of the actual saving. Confirmed:
> `text-3d` and `cgui-gallery` render identically with the exemption in place.

### Removing the coremod — the hotswap tangle was narrower than feared

The transformer's entire purpose was rewriting call sites so `CrystalGLRedirects` could feed `GLStateMirror`
(`CoverageMatrix` = the redirect table, `CrystalGLRedirects` = the targets). One thing, four files.

`TransformHelper` had **two** modes and only one was ours:

- *CrystalGraphics-only* — re-applies our transformer. Dead with it, and it was the **default**.
- *full-chain* — re-applies the entire LaunchWrapper chain, **Mixins included**. Real value independent of
  the mirror, and it was gated behind an opt-in flag defaulting to **off**.

So hotswap survives with the CG-only branch removed and full-chain unconditional. Net effect: hot reload now
re-applies Mixins **by default**, which it previously did not unless you knew to pass
`-Dcrystalgraphics.hotswap.fullChain`. A small improvement that fell out of the cleanup.

`CallFamily` was kept at this point, defended as "the Core/ARB/EXT waterfall used by nine files outside
`gl/state`". **That was wrong, and it has since been deleted.** The nine were files that *mentioned* the
symbol; every use was a `protected CallFamily callFamily() { return CONSTANT; }` override of an abstract
method with zero callers. Its consumer `CrossApiTransition` was already gone, so the javadoc referred to a
deleted class. `CgFrameBuffer.wrap()` took, validated and stored one — and had no callers either.

`CgCapabilities.FramebufferPath` in `platform` is the correct home for the concept and already existed:
capability detection belongs beside the capability probe, not in a state package that tagged bindings for a
mirror that no longer exists.

> **Method note, having now produced two wrong "load-bearing" claims in one session:** `grep -l` counts
> files that *mention* a symbol — imports, javadoc, overrides. It cannot tell a caller from a producer.
> Search for invocations before defending anything.

### Runtime verification

| Check | Result |
|---|---|
| `text-3d`, 1143 frames | ✅ no exception; MSDF and bitmap glyphs both correct |
| `cgui-gallery` | ✅ renders correctly — real glyphs, sprites, disabled states; not the filled-quad regression |
| `:platform:test` (19) · `:core:test` | ✅ green |

Still outstanding: a `doBind` re-measure against the 73.56 ms / 2.16 ms baseline — the main-thread profiling
scopes were not enabled in the verification run, so that number is unconfirmed, not regressed.

> ### ⚠️ Not yet verified at runtime
> ~~The switchover has **not been run**.~~ *(Run now — see above. One real bug found and fixed; the
> caveat below is kept because it is what found it.)* Deduplication moved from whole-value to per-field comparison and
> restore now re-issues through `CgGL`, so behaviour differences will not show up in any unit test — every
> bug this subsystem has had was a *missing* GL call, which renders wrongly and never throws.
> Before trusting it: run `cgui-gallery` and `text-3d` with `-Dcrystalgraphics.state.verify=true`, compare
> staleness against the ~1-per-1,100-frames baseline, re-measure `doBind` against 73.56 ms / 2.16 ms, and
> **look at the screen** — including bitmap-tier glyphs.

### Why

One path instead of two. Today the typed records `apply()` → manager → record + emit, while a raw `CgGL`
write only *invalidates*. That asymmetry needs an observer, a bridge, a build-time quality gate, and a rule
nobody can be relied on to follow. At `CgGL` level every caller — typed records, raw `CgGL`, other mods — is
tracked and deduplicated identically, and all of that machinery disappears.

It is also the mainstream design: Blaze3D's `GlStateManager` and Angelica's `GLStateManager` both dedupe at
the individual GL call, not at composite state objects.

> **A correction that motivated this.** I previously argued against recording values at the `CgGL` level
> because the observer sees *intent*, not *outcome* (Iris can cancel downstream). That argument was
> overstated: `apply()` already records `tracked[i] = desired` immediately after `emit()`, which is equally
> above the backend. The exposure is unchanged by this move; the mitigation in both designs is boundary
> re-adoption.

### Target shape — 5 platform classes, 7 core value types

**`platform/gl/`** + **`platform/gl/state/`** — the entire state machine, next to the calls it tracks. `CgGlStateManager` sits beside `CgGL` because `CgGL` consults it on every setter; the value types, the scope API and the provider SPI live one level down in `.state`:

| Class | Role |
|---|---|
| `CgGL` | Unchanged as dispatch. Each state setter consults the manager first and returns early when the write is redundant. |
| **`CgGlStateManager`** | **Kept.** Same name, same role — it *is* the engine, and always was. Moves to `platform` (it must own the shadow, and the shadow must be where the dedup happens) and its shadow changes from a `CgStateGroup[]` to flat fields. Everything else about it survives: scope stack, adoption, trust bits, invalidation. |
| **`CgGlState`** | **Kept.** Same name, same public API — `save(slots…)`, `manager()`, `setProvider(…)`, `invalidateAllIfPresent()`, `reset()`. Moves to `platform` alongside the manager. |
| `CgGlSlot` | Moved from `core/api/state`. Its only imports today are javadoc references to two classes that also move, so it relocates cleanly. |
| `CgGlScope` | Moved from `core/gl/state`. Unchanged shape. |
| `CgGlStateProvider` | Moved. Adoption SPI, with the `glGet` default folded in as its base implementation. |

> **An earlier draft of this section proposed deleting `CgGlStateManager` and `CgGlState` in favour of a new
> `CgGlStateTracker`.** That was churn dressed up as simplification: the "new" class had exactly the
> manager's responsibilities, so the rename inflated the deletion count, discarded established vocabulary,
> and would have made the diff far larger than the actual change. The manager and its facade are the two
> things worth keeping by name — what genuinely disappears is listed below.

**`core/api/state/`** — authoring value types only, externally unchanged:
`CgBlendState` · `CgDepthState` · `CgCullState` · `CgStencilState` · `CgAlphaState` · `CgColorMask` ·
`CgRenderState`. Their `apply()` simply calls the corresponding `CgGL` setters; `CgGL` decides whether the
call happens.

**Platform providers** stay where they are: `AngelicaStateProvider` (mc1710), `Blaze3DStateProvider` (mc1201).

### Every class removed — 15

| Deleted | Because |
|---|---|
| `CgStateGroup` | The one-method abstraction existed so the manager could be domain-agnostic. With field-level dedup there is nothing left for it to abstract. |
| `CgGlWriteObserver` | `CgGL` **is** the tracker now — nothing to observe. |
| `CgGlStateWriteObserver` | Same. |
| `CgGlGetStateProvider` | Folded into `CgGlStateProvider` as its default behaviour. |
| `CgFboState` · `CgProgramState` · `CgTextureUnitsState` · `CgVertexInputState` | Become fields on the shadow. |
| `CgColorMaskState` | Becomes a packed `int` field (the 8×4-bit trick survives as a field, not a type). |
| `CgViewportState` · `CgScissorState` · `CgPolygonOffsetState` · `CgPolygonModeState` · `CgLineWidthState` · `CgPointSizeState` | Become fields. Nothing authors these as values — they were created only to satisfy `CgStateGroup`. |

Also removed: the `cgStateWriteGuard` Gradle task. A raw `CgGL` write becomes fully equivalent to the typed
path, so there is nothing left to discourage.

**Net: ~25 state-related classes → 10**, and three whole mechanisms (observer, bridge, guard) disappear.

Nothing is renamed. `CgGlStateManager` and `CgGlState` keep their names and their jobs; three more classes
move package unchanged; fifteen genuinely stop existing.

### Sketch — what `CgGlStateManager` becomes

```java
package com.crystalgraphics.platform.gl.state;

public final class CgGlStateManager {

    // ── The shadow: flat fields, one nested holder so a snapshot is one copyFrom ──
    private static final class Shadow {
        boolean blendEnabled; int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
        int blendEqRgb, blendEqAlpha;
        boolean depthTest, depthMask;  int depthFunc;
        boolean cullEnabled;           int cullFace, frontFace;
        boolean stencilTest;           int stencilFunc, stencilRef, stencilValueMask,
                                           stencilWriteMask, stencilFail, stencilZFail, stencilZPass;
        boolean alphaTest;             int alphaFunc;  float alphaRef;
        int     colorMaskPacked;                        // 8 targets x 4 bits, as today
        boolean scissorTest;           int scissorX, scissorY, scissorW, scissorH;
        int     viewportX, viewportY, viewportW, viewportH;
        boolean poFill, poLine, poPoint; float poFactor, poUnits;
        int     polyModeFront, polyModeBack;
        float   lineWidth, pointSize;
        int     programId;  CallFamily programFamily;
        int     drawFbo, readFbo;  CallFamily fboFamily;   // family: see design point 1
        int     activeUnit; final int[] boundTexture2D = new int[MAX_UNITS];
        int     vao, arrayBuffer, elementArrayBuffer;

        void copyFrom(Shadow o) { /* ~50 assignments */ }
    }

    private final Shadow current = new Shadow();
    private int unknownMask = ALL_UNKNOWN;          // one trust bit per CgGlSlot, unchanged
    private boolean emitting;                        // suppresses self-notification during restore

    /** Diagnostics. Plain fields because CgProfiler lives in core and cannot be reached from here. */
    public long callsIssued, callsSkipped, adopted;

    // ── Per-call dedup: one method per GL setter, called BY CgGL ──────────────
    //
    // Returns true when the value actually changed, i.e. "yes, tell the driver". Records as it goes,
    // so there is no separate recording step to forget.

    public boolean depthMaskChanged(boolean flag) {
        if (isTrusted(DEPTH) && current.depthMask == flag) { callsSkipped++; return false; }
        current.depthMask = flag; trust(DEPTH); callsIssued++; return true;
    }

    public boolean blendFuncChanged(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (isTrusted(BLEND)
                && current.blendSrcRgb == srcRgb && current.blendDstRgb == dstRgb
                && current.blendSrcAlpha == srcAlpha && current.blendDstAlpha == dstAlpha) {
            callsSkipped++; return false;
        }
        current.blendSrcRgb = srcRgb; current.blendDstRgb = dstRgb;
        current.blendSrcAlpha = srcAlpha; current.blendDstAlpha = dstAlpha;
        trust(BLEND); callsIssued++; return true;
    }
    // ... one per CgGL state setter, ~28 of these. Each is 3-5 lines and mechanical.

    // ── Scopes: unchanged externally ─────────────────────────────────────────

    public CgGlScope save(CgGlSlot... slots) {
        Frame f = frames[depth++];
        f.mask = 0;
        boolean outermost = depth == 1;
        for (CgGlSlot slot : slots) {
            if (outermost || !isTrusted(slot)) adopt(slot);   // Tier 0, unchanged
            f.mask |= 1 << slot.ordinal();
        }
        f.saved.copyFrom(current);        // whole-shadow copy; cheaper than it looks, ~12x/frame
        return f;
    }

    /**
     * Restore re-issues through CgGL rather than writing GL directly.
     *
     * That is the point of the whole move: a domain nobody disturbed compares equal and issues NOTHING,
     * and there is no second write path that can drift from the first. What used to be
     * CgStateGroup.emit() lives here as one switch instead of twelve classes.
     */
    private void restore(Frame f) {
        emitting = true;
        try {
            for (CgGlSlot slot : CgGlSlot.values()) {
                if ((f.mask & (1 << slot.ordinal())) == 0) continue;
                reissue(slot, f.saved);
            }
        } finally { emitting = false; depth--; }
    }

    private void reissue(CgGlSlot slot, Shadow s) {
        switch (slot) {
            case DEPTH:
                if (s.depthTest) CgGL.glEnable(GL_DEPTH_TEST); else CgGL.glDisable(GL_DEPTH_TEST);
                CgGL.glDepthFunc(s.depthFunc);
                CgGL.glDepthMask(s.depthMask);
                break;
            case BLEND: /* ... */ break;
            // 16 cases. This is the ONLY per-domain code in the class, and it replaces
            // twelve CgStateGroup implementations.
        }
    }

    // ── Adoption and trust: unchanged in behaviour ───────────────────────────
    private void adopt(CgGlSlot slot) { provider.read(slot, current); adopted++; trust(slot); }
    public void invalidate(CgGlSlot... slots) { for (CgGlSlot s : slots) unknownMask |= 1 << s.ordinal(); }
    public void invalidateAll() { unknownMask = ALL_UNKNOWN; }
}
```

**What this buys, concretely:**

| | today | after |
|---|---|---|
| dedup granularity | whole record | **per field** — `glDepthMask(false)` twice elides |
| paths that write state | two (typed records, raw `CgGL`) | **one** |
| per-domain code | 12 `CgStateGroup` classes | **one 16-case `reissue` switch** |
| restore | separate diff-and-write path | **re-issues through the same dedup** |
| notification machinery | observer + bridge + guard | **none** |

**What it costs:** ~28 small dedup methods in the manager, each 3–5 lines and mechanical, plus the
`reissue` switch. That is the trade — a little repetition in one file, against twelve classes, an SPI, a
bridge and a build-time guard.

**The provider SPI changes shape**: `read(slot)` returning a record becomes `read(slot, Shadow)` filling
fields, since there are no records to return. `AngelicaStateProvider` and `Blaze3DStateProvider` port
mechanically — they already read individual fields and assemble records, so they simply stop assembling.

### Two design points that need deciding during the work

1. **FBO call families — RESOLVED, and simpler than feared.** The worry was that
   `glBindFramebuffer(target, fbo)` carries no family, so the cross-family release rule would be lost.

   It is derivable from the target. All three family classes call the **same** `CgGL.glBindFramebuffer`:
   `CgCoreFrameBuffer:49` and `CgArbFrameBuffer:52` pass a normal target, `CgExtFrameBuffer:55,64,94` pass
   `GL_FRAMEBUFFER_EXT`. And Core vs ARB do not need distinguishing at all —
   `ARB_framebuffer_object` was specified to match GL 3.0 core semantics and **shares its object
   namespace**; only `EXT_framebuffer_object` is the older, incompatible one.

   So the rule reduces to: `target == GL_FRAMEBUFFER_EXT` → EXT family, anything else → Core/ARB, and
   `glBindFramebufferCompat` → the MC wrapper family. The manager derives `fboFamily` itself, no parameter
   and no plumbing through the framebuffer classes. **The highest-risk item in this move is now the
   cheapest.**

2. **Counters.** `CgProfiler` lives in `core`, and `platform` cannot depend on `core`. Proposed: the manager
   keeps plain public `long` counters and core's profiler reads them when dumping. No callback, no SPI —
   one fewer moving part than the alternative.

### Migration order

1. Change `CgGlStateManager`'s shadow from `CgStateGroup[]` to flat fields, with per-field dedup, **in
   place** — still in `core`, still record-driven at the edges. Existing tests must stay green; this step
   should be invisible from outside.
2. Move `CgGlStateManager`, `CgGlState`, `CgGlSlot`, `CgGlScope`, `CgGlStateProvider` to `platform` as one
   commit. **Breaks CrystalGUI's two imports in `CgUiPaintContext`** — a coordinated two-repo change, but
   only two lines.
3. Wire each `CgGL` state setter to the manager.
4. Reduce the typed records to plain `apply()` → `CgGL`; delete `CgStateGroup`.
5. Port the providers to fill the flat shadow.
6. Delete the 15 classes and the guard task.

Step 1 is deliberately first and deliberately invisible: it is the only step that changes *behaviour*
(record-level → field-level dedup), so it lands alone, against the existing test suite, with nothing else
moving. Steps 2 onward are relocation and deletion — large diffs, no semantic change. Combining them would
make a rendering regression impossible to bisect, which is exactly how this subsystem burned four rounds
already.

### Verification — non-negotiable given the history

- **Parity first:** land with `-Dcrystalgraphics.state.noDedup` still honoured, and confirm behaviour matches
  the current build before removing the old path.
- **`-Dcrystalgraphics.state.verify=true` on `text-3d` and `cgui-gallery`** must stay at ~1 stale report per
  1,100 frames. Any regression means the field-level shadow is lying somewhere the record-level one was not.
- ✅ **Re-measure `doBind`.** Baseline was 73.56 ms / 1010 frames, max 2.16 ms; "a field-level design should
  be no worse, or the move is not paying for itself." **Result: 65.05 ms / 1124 frames, max 1.06 ms** — it
  pays for itself.
- Visual check on `cgui-gallery` **and** `text-3d`, including bitmap-tier glyphs — three of this subsystem's
  four bugs were invisible to the test suite and visible only on screen.

---

## TODO — everything remaining, updated 2026-07-31

**The engine is done, deleted-down, documented, measured and green.** Every claim in this document now has
evidence behind it. What is left is one blocked integration, two deferrals, and somebody else's flaky tests.
Nothing below blocks anything above it.

### Measured outcome — V2 confirmed 2026-07-31

`text-3d`, 1124 frames. V2 is **faster than V1-after**, not merely equivalent: per-field deduplication
outweighs always issuing `PROGRAM`/`FBO`.

| | before | V1 after | **V2 (current)** |
|---|---|---|---|
| `doBind.stateSave` | 1,599 ms / 838 frames | 0.00 ms — scope removed | **0.00 ms** |
| whole `material.doBind` | ~1,650 ms | 73.56 ms | **65.05 ms** |
| worst single frame | **346.8 ms** | 2.16 ms | **1.06 ms** |
| `doBind` p50 | — | 0.050 ms | **0.044 ms** |
| `doBind.renderState` | — | — | 18.52 ms total, p50 0.011 ms |

Shadow staleness under `-Dcrystalgraphics.state.verify=true`: **1 report in ~1,130 frames** (frame 1, before
any boundary exists; self-corrects).

> **Where these numbers live, because it cost a wrong "unmeasured" conclusion twice.**
> `CgProfiler.endFrame()` *consumes and resets* per-frame data into the scene's own report, so main-thread
> scopes are written to **`harness-output/text-3d/kanji-warmup-profile.csv`** (columns `matDoBindMs`,
> `dbRenderStateMs`, `dbStateSaveMs`) — **not** to the `cg-profile-*-all-threads-*.txt` dump, whose `main`
> section is legitimately empty for that reason. The `*-trees.txt` file holds only a warmup sample
> (~59 calls), not the window. Read the CSV.

> **Do not re-add a per-bind scope to `CgMaterial` to "get parity".** The 73.56 ms figure was *already*
> measured with the scope removed, so V1-after and V2 are parity by construction; re-adding it recreates the
> V1-**before** condition instead. It also cannot be done — material bind/unbind is not LIFO
> (`CgQuadRenderer.useMaterial` binds every call, unbinds only on change), so the scope throws "closed out of
> order". `CgMaterial.doBind` carries both reasons in a comment.

### 🔴 1 — Blocked on you: re-add `mc1201`, then compile and test together

**`Blaze3DStateProvider` is written but has never been through a compiler**, and is still on the *old*
provider signature (pre-`read(slot, shadow)`), because `mc1201` is absent from `settings.gradle.kts`.

- [ ] Re-add `mc1201` to `settings.gradle.kts`
- [ ] Port it to the fill-the-shadow signature, then compile — **expect errors**; field names and Iris
      semantics are source-verified, the syntax is not
- [ ] Wire into `PlatformService1201` (mirroring `PlatformService1710.onPreInit()`)
- [ ] Run with a shader pack **active** — the Iris locks are `false` when idle, so an idle run proves
      nothing about the gate
- [ ] Run with `-Dcrystalgraphics.state.verify=true` and confirm no stale-shadow reports

### 🟡 2 — Needs a real 1.7.10 instance

- [ ] **Runtime-test `AngelicaStateProvider`.** Compiles, never run — and it no longer has a predecessor,
      since the mirror it replaced is deleted. Reflection is fail-soft (any miss falls back to `glGet`), so
      the risk is "no speedup" rather than breakage — but that also makes total failure **silent**. Check
      the `adopted` counter, or temporarily log on fallback.

### 🟠 3 — Arising from this session's work

- [ ] **`hostForeign` has zero callers.** The primitive is unit-tested but has never wrapped a real
      `ItemRenderer`. Its first genuine use is the real test, and that needs `mc1201`. **When it is used,
      remember the other half**: set the state MC cares about through MC's own API first, or its shadow is
      as stale as ours was.
- [ ] **Decide whether `TEXTURES`/`VERTEX_INPUT` should also be exempt.** They are the remaining exposure
      inside an *unmarked* foreign block. Kept deduplicated deliberately (high call volume), but that is a
      bet, not a proof — revisit if `verify` mode ever reports them.
- [ ] **Regenerate `RecordingGlBackend` when `CgGLBackend` gains methods.** It is generated, and the
      compiler enforces completeness (an unimplemented abstract fails the build), so this is a "you will be
      told" item rather than a silent rot risk. Regenerate; do not hand-edit.

### 🟢 4 — Deferred by decision, not blocked

- [ ] **Re-enable CrystalGraphics' import guard** (`core/build.gradle.kts`, commented out inside a disabled
      dual-pipeline experiment). `AGENTS.md` carries a warning instead of claiming enforcement it lacks.
      Copy CrystalGUI's, which is active. *(The `cgStateWriteGuard` once cited here as the working pattern
      is gone — V2 made the rule it enforced unnecessary.)*
- [ ] **Dedicated state-thrash benchmark scene.** N materials alternating render states, measuring
      `callsIssued` / `callsSkipped` directly. `text-3d` found the original stall by accident. Deliberately
      last: it produces a number nobody has asked for.

### ⚪ 5 — Not ours, but it bites

- [ ] **Flaky MSDF tests:** `CgMsdfFieldStorageTest`, `CgMsdfGenerationCostTest`, `CgMsdfSyncBudgetTest`
      assert timing and generation budgets, fail under machine load, pass in isolation. Hit three times
      during this work. They make `:core:check` unreliable as a gate — **a contended run is evidence of
      nothing**, which cost real time here. Worth converting to deterministic assertions.
- [ ] **CrystalGUI's submodule pointer** to CrystalGraphics is stale. Left alone deliberately: that repo
      also holds another agent's in-flight P6 work, and bumping the pointer would drag it into the commit.

---

### Done

**Engine:** manager in `platform.gl`, API + SPI in `platform.gl.state` · dedup inside `CgGL`'s 28 setters ·
per-field shadow · restore re-issued through `CgGL` (no second write path) · `hostForeign` · `PROGRAM`/`FBO`
dedup exemption · `forcing` for stale-domain restore · per-VAO element-buffer handling · frame- and
pass-boundary invalidation · render-thread assertion · `verify` and `noDedup` diagnostics.

**Deleted:** 20 classes in the switchover (`CgStateGroup`, the old framework, the observer pair, the ten
per-domain records, the guard task, four test classes) plus the entire coremod, `GLStateMirror` and two
coremod test suites — **~3,300 further lines**.

**Tests:** 27 in `platform`, including `RecordingGlBackend` — a generated 141-method double that made the
restore path testable for the first time across *two* architectures, and immediately found a real bug.

**Platform:** `AngelicaStateProvider` (mc1710, wired, compiles, unrun) · `Blaze3DStateProvider` (mc1201,
written, uncompiled, old signature) · Iris/Oculus interception verified exhaustively and gated.

**Docs:** root `AGENTS.md`, `gl/state/`, `api/state/`, `gl/buffer/`, `mc/shader/` — plus three stale claims
corrected that predated this work (`CgRenderState`'s "four slots" when it has six; buffer bindings described
as mirror-tracked; `bindScoped()` described via `GLStateMirror` and a `CgStateBoundary` that never existed).

**Declined with reasoning:** D6 texture-unit split (restore would unbind CrystalGraphics' own textures, for
3 units of 32) · a separate Iris provider (impossible in principle — Iris keeps no general-purpose mirror
to read) · deleting the hotswap package
(its full-chain mode re-applies Mixins, which outlives the coremod).

---

# ─── Everything below is the original design record ───

**Historical, from 2026-07-30, before any code existed.** It is kept because the *reasoning* is still the
best account of why the design is shaped this way — the four-tier boundary model, why interception cannot
be a foundation, why Iris cannot have its own provider. But it describes intent, not the code.

Where the two disagree, **the code and the sections above win.** In particular: "Implementation order",
"Decisions taken — step 1 is unblocked" and the class sketches are finished work, not a plan; the shadow
sketch is explicitly marked superseded; and the six-domain dedup allow-list it argues for was later widened
to fourteen and then narrowed differently (see *`PROGRAM` and `FBO`* above).

---

## TL;DR

`CgGlState.save(...)` captures GL state by issuing `glGet*`. Every `glGet` is a driver
synchronisation point. `CgMaterial.doBind()` does this **per material bind**, ~25 `glGet` calls a
time, and the worst observed frame paid **346.8 ms** in `doBind.stateSave` alone — immediately
followed by 286.5 ms, a ~0.64 s freeze in an otherwise 134 fps run.

The fix is a **shadow-state manager behind the existing API**:

- **`CgGlState` / `CgGlScope` / `CgGlSlot` stay exactly as they are** — the API is good; only the
  `glGet` inside `capture()` was ever the problem. `CgGlStateManager` becomes the engine underneath, so
  ~12 existing call sites (and all of CrystalGUI) get faster **without being edited**.
- The manager diffs against a shadow and issues only calls that actually change something. **No `glGet`
  in the steady path.**
- `save(...)` **adopts or snapshots by nesting depth**: outermost pours truth from the platform, nested
  copies the shadow. Callers never declare which they are.
- The shadow is made true at a small, fixed set of **transfer points** where control passes between
  Minecraft and CrystalGraphics. Each platform *pours* its own state manager into ours there.
- `CgMaterial`'s **per-bind** save is deleted — but only after the outermost-scope baseline exists, since
  null render-state slots inherit from it (blocker §4).

---

## Why interception cannot be the foundation

`GLStateMirror` is fed exclusively by the 1.7.10 ASM coremod (`CrystalGLRedirects`, 9 call sites).
The theory was that if you redirect every GL call site in the process, the mirror becomes
omniscient and `glGet` becomes unnecessary.

**That theory is dead, and not for a fixable reason.** Our redirector is modelled on Angelica's,
which does the same thing to the same call sites — and in observed cases **Angelica's redirector was
redirecting our redirector into its own.** Two transformers competing for the same bytecode cannot
both be authoritative, and neither ends up omniscient. Add any third mod doing raw GL and the
guarantee is gone entirely.

So the mirror's premise fails on its *best* target. On the others it never even applies:

| Target | Mirror fed? | Effective behaviour |
|---|---|---|
| harness | no | `UNKNOWN` → `glGet` always |
| mc1201 forge / neoforge / fabric | no — no coremod exists | `glGet` always |
| mc1710 | FBO / PROGRAM / texture only | none of which `doBind` saves |

**Conclusion:** state truth can never be *derived* by observation. It must be *asserted* (we know
the value because we just wrote it) and *poured* (someone with better information hands it to us).

---

## Existing rot to remove on the way through

Found while reading the current implementation. All of it dies with the rewrite; recorded so none
of it gets faithfully reimplemented.

| Location | Problem |
|---|---|
| `GLStateMirror.markUnknown()` | **Zero callers.** The invalidation the javadoc calls essential is never invoked, so a populated mirror is trusted forever with no way to mark it stale. |
| `CgGlState.isGapOnlyModeSafe()` :114 | `coreMod.getMethod("isGapOnlyMode");` **discards its result**; `gapOnlyMode` stays null; the next line NPEs into `catch (Throwable ignored)`. Gap-only mode has never once been detected. |
| `CgGlStates.AlphaTestState.isCore()` | `if (!CORE) CORE = ...detect().isCoreProfile();` re-queries on every capture while the memo is false — i.e. always, on 1.7.10. |
| `CgMaterial.stateScope` :145 | A **single mutable field** holding an open scope. Interleaved or re-entrant binds clobber it: the earlier scope is never closed and its state never restored. `drawChain` walks `nextPass` materials through this path. |
| `CgGlStates.TextureState.capture()` | Loops `glActiveTexture` + `glGetInteger` over *every* texture unit — up to 32 sync points for one slot. |

---

## Two structural facts that make this cheap

**1. The pass-level boundary already exists and is already correct.**
`CgRenderPipeline.executeOpaquePass` (`:314`) and `executeTransparentPass` (`:344`) each wrap their
*entire* pass in exactly one `CgGlState.save(...)`. `CgUiPaintContext.beginFrame` (CrystalGUI,
`:244`) does the same for the UI burst.

So this is not "invent a boundary." The boundaries are already drawn in the right places. The bug is
that `CgMaterial.doBind` (`:729`) opens a **second, nested save per material bind** *inside* them.
The outer scope already guarantees the handback to Minecraft; the inner one exists only to isolate
material A from material B — which is exactly what diffing gives for free.

**2. `CgRenderState.apply()` does not diff.** It writes unconditionally. So today's `doBind` is:

```
25 glGet sync points  →  unconditional writes  →  unconditional restore writes
```

nested inside a scope already doing the same job for the whole pass. The reads buy nothing.

---

## The model

> Truth enters only at declared transfer points. Between them the shadow is authoritative by
> assertion, because we wrote it. Staleness is bounded by boundary frequency, not by hope.

Four tiers.

### Tier 0 — ADOPT (the sync point; the only place truth enters)

The platform pours its state manager into our shadow. **Six sites, all statically identifiable,
none inside a loop:**

| # | Site | Why it is a transfer point |
|---|---|---|
| 1 | `CgGraphicsLifecycle.initContext` | brand-new context; nothing is known |
| 2 | `CgGraphicsLifecycle.onResize` | viewport / scissor moved by someone else |
| 3 | `CgGraphicsLifecycle.onOpaquePass` | MC has just handed us the frame |
| 4 | `CgGraphicsLifecycle.onTransparentPass` | **mandatory** — MC rendered translucent terrain, tripwire and particles since #3 |
| 5 | `CgUiPaintContext.beginFrame` | entered from an unrelated HUD/GUI hook, arbitrary state |
| 6 | asset reload (F3+T, `CgAssetReloader`) | the reload path touches GL |

3–4 hits per frame. **#4 is non-negotiable** — the gap between our two passes is guaranteed foreign
mutation, and skipping it is the one shortcut that would reintroduce silent corruption.

### Tier 1 — RELEASE (diff-restore, no read)

Symmetric partner at burst exit. Write back only those states whose shadow differs from the snapshot
adopted at Tier 0. This is where `CgGlScope`'s *semantics* survive — as a diff against known-good
values rather than a blind rewrite of every captured slot, and with zero `glGet`.

### Tier 2 — INVALIDATE (mark dirty; zero GL calls)

Foreign code *may* have run, but a full refill is not worth it. Marks the shadow dirty so the next
`apply` writes unconditionally instead of diffing. One boolean per state group. Sites:

- Around `CgPreDrawHook.apply(...)` — `CgDepthPrepassRenderer:98` and `CgForwardRenderer:86`. User
  code, unbounded in what it may touch.
  > Note: `CgTransparentRenderer` does **not** invoke `preDrawHook` at all. That is a pre-existing
  > asymmetry, not something this plan introduces — worth confirming separately whether it is
  > intentional.
- Around `CgLifecycleListener` dispatch (`onInit` / `onFrame` / `onDestroy`) — third-party listeners.
- Around `glBindFramebufferCompat` / `OpenGlHelper` routing on 1.7.10 — the exact wrapper Angelica
  hijacks, so anything behind it is untrustworthy by construction.

### Tier 3 — STEADY (diff and skip)

Everything else, including `CgMaterial.doBind`. `apply(CgRenderState)` compares against the shadow,
issues only what changed, updates the shadow. **The per-bind save is deleted outright.**

---

## The platform seam

One new SPI interface in `platform/`, one method:

```java
public interface CgGlStateProvider {
    /** Pour this platform's authoritative view of GL state into {@code into}. */
    void adopt(CgGlStateShadow into);
}
```

Registered through `CgPlatform` like every other service. Implementations, in descending fidelity —
the point being that this **degrades gracefully** rather than requiring omniscience anywhere:

| Platform | Source of truth | `glGet` per adopt |
|---|---|---|
| mc1710 + Angelica | Angelica's `GLStateManager` — public Lombok getters | **0** |
| mc1201 ×3 (1.20.1 / 1.20.4) | Blaze3D `GlStateManager` — *partial*, via cached reflection | ~13 full · **3** for a `doBind` burst |
| mc1710 vanilla | batched `glGet` sweep | ~40 |
| harness | batched `glGet` sweep | ~40 |

Worst case is **today's cost paid 3–4× per frame instead of ~25× per material bind.**

> Full per-group derivation, verified against extracted sources, is in
> [the provider contract](#the-provider-contract) below — including which Blaze3D groups are pure
> pass-through and why 1.7.10-with-Angelica, counter-intuitively, is the **best** target rather than
> the worst.

> **1.20.x has a second, independent reason to stay coherent.** `GL1201Backend` already routes
> CrystalGraphics' *writes* through `RenderSystem` / `GlStateManager` deliberately. So for the groups
> Blaze3D does track, MC's shadow and ours cannot drift from our own activity — Tier 0 there is
> catching *other mods'* raw GL, not our own.

---

## Where this sits: `CgGL` is already the `RenderSystem` half

Worth stating explicitly, because the older research (see below) predates it. Mapped onto Blaze3D's
two-class split:

| Blaze3D | CrystalGraphics | Status |
|---|---|---|
| `RenderSystem` — public API, dispatch, thread gate | `CgGL` (1202 lines, 482 static methods, **one** mutable field: `backend`) + `CgGLBackend` per platform | **already exists** |
| `GlStateManager` — shadow + per-call dedup | — | **missing; this plan** |

`CgGL` is a *pure stateless dispatch facade*. That is the right shape for it and it should stay that
way. `CgGlStateManager` slots in **above** it:

```
core  →  CgGlStateManager  (shadow, diff, dedup)  →  CgGL  (dispatch)  →  CgGLBackend  (platform)
```

> **Enforcement hazard.** Because the manager sits *above* `CgGL`, any core code calling
> `CgGL.glEnable` / `glBlendFunc` / `glDepthMask` / etc. **directly** silently bypasses the shadow and
> desynchronises it. With 482 static methods on `CgGL` and core calling them freely today, this is the
> most likely way the rewrite gets quietly broken six months from now.
>
> Mitigation: mirror the existing "no raw GL in core" import-guard pattern with a build-time check
> that core does not call the *state-setting* subset of `CgGL` outside `gl/state/`. Query and
> resource methods (`glGenTextures`, `glTexImage2D`, …) stay freely callable — only the state setters
> are restricted. Decide the exact method list when the manager's surface is fixed.

---

## Decision: scrap `GLStateMirror`, the redirector, **and** the 1.7.10 coremod

Recorded 2026-07-30 after reading every redirect body. This goes further than "delete the mirror,"
and the reason is that the redirector turns out to have no independent purpose.

### All nine redirects are the same shape, and it is mirror-feeding

`CrystalGLRedirects` has exactly nine public methods — `glBindFramebuffer` ×4 (Core/ARB/EXT/MC's
`OpenGlHelper`), `glUseProgram` ×2, `glActiveTexture` ×2, `glBindTexture` ×1. Every one is:

```java
if (!GLStateMirror.isInRedirect()) GLStateMirror.on<Thing>(...);
GLStateMirror.enterRedirect();
try  { /* the real GL call */ }
finally { GLStateMirror.exitRedirect(); }
```

The single exception is `bindFramebufferMc`, which additionally waterfalls GL30 → ARB → EXT →
`OpenGlHelper`. That looks like independent value but is not: it merely *reproduces* the dispatch
`OpenGlHelper.func_153171_g` already performs internally. It is the cost of intercepting, not a
feature.

**So the mirror dying leaves the redirector with nothing to do.** `CrystalGraphicsTransformer`,
`CrystalGLRedirects` and `CoverageMatrix` all go with it.

### And that empties the coremod

`CrystalGraphicsCoremod` (an `IFMLLoadingPlugin`) returns `null` from `getModContainerClass()`,
`getSetupClass()` **and** `getAccessTransformerClass()`, and does **not** implement
`IEarlyMixinLoader` — mixins are loaded independently from `mixins.crystalgraphics.json` via
`CrystalGraphicsEarlyMixins` (an `IMixinConfigPlugin`), so `CgRenderHook` is unaffected.

Its only two jobs are `getASMTransformerClass()` (dies with the transformer) and Angelica detection
in `injectData()` — and detection is a plain classpath probe for
`com/gtnewhorizons/angelica/glsm/GLStateManager.class`, which needs no loading plugin whatsoever.

> **Net effect: CrystalGraphics stops being a coremod on 1.7.10.** No LaunchWrapper plugin, no
> process-wide bytecode transformer, no class-load-time scan of every loaded class, no coremod
> sorting-index hazards, and no transformer-versus-transformer conflict with Angelica. This is the
> largest single reduction in blast radius available anywhere in the codebase, and it falls out of
> the state rewrite for free.

### What we give up, and why it costs nothing

Observing *other mods'* FBO / program / texture binds. In an assert-and-adopt design that
information is never consulted: we need only **(a)** our own writes, which pass through `CgGL` by
construction, and **(b)** a pour of truth at boundaries. Foreign observation is load-bearing only for
a design that *reads its shadow to skip work it did not perform* — which is the design being
replaced.

Also lost: the FBO-tracking assertion in `mc1710/…/CrystalGraphicsIntegrationTest` (lines 129–150).
It should be replaced with an equivalent assertion against the new shadow, not simply deleted.

### **Keep** Angelica detection — it becomes *more* valuable

Counter-intuitive, so spelled out. `isAngelicaPresent()` / `isGapOnlyMode()` currently has two
consumers: the transformer (dies) and the broken `CgGlState.isGapOnlyModeSafe()` (dies). Naively it
is orphaned.

But in this design Angelica's presence is exactly what tells the 1.7.10 `CgGlStateProvider` whether
it can **pour from Angelica's own `GLStateManager` shadow at zero `glGet` cost**, or must fall back
to a `glGet` sweep. So detection survives — relocated out of the coremod into an ordinary runtime
classpath probe, and *fixed* on the way (the current consumer has never once returned `true`).

---

## Backbone — how it actually works

Concrete enough to implement from. Four operations, one shadow, one provider contract.

### Layering and ownership

```
core  →  CgGlStateManager  →  CgGL  →  CgGLBackend  →  driver
              │
              └── CgGlStateProvider.adopt()   (platform pours truth in)
```

Singleton, `CgGlStateManager.get()`, matching the other registries. Reset by
`CgGraphicsLifecycle.destroyContext()` — the shadow describes *a* context and must not outlive it,
the same trap `CgUiPaintContext.destroy()` exists to close.

### The shadow — **superseded by the implemented design**

> **Revised 2026-07-30, and this reverses the section below.** The flat-primitive-field shadow specified
> here optimises the wrong thing. Each of 16 domains would need storage, diff, copy, restore and adopt
> code — five near-identical paths × 16 domains, which is exactly how `CgGlStates` reached 630 lines of
> near-duplicate inner classes. It would have produced the jungle.
>
> **What was actually built:** every domain is an immutable `record` implementing `CgStateGroup`, an
> interface with **one** method — `emit()`. The record supplies the rest:
>
> | Manager needs | Provided by |
> |---|---|
> | storage | one reference per slot — the record *is* the shadow |
> | redundancy check | the record's generated `equals()`, which cannot drift from the field list |
> | snapshot / copy | reference assignment (values are immutable) |
> | emit GL calls | `emit()` — **the only per-domain code that exists** |
>
> **`CgGlStateManager` therefore contains zero per-domain code**; a seventeenth slot needs no edit to it.
> And critically, **restore is just `apply` with an older value** — so there is no separate restore path
> that can disagree with the apply path. That divergence was real in the old design, where
> `FboState.restore()` carried call-family logic `capture()` knew nothing about.
>
> On allocation, which is what the flat-field design was protecting: the steady path allocates
> **nothing**, because callers apply shared presets (`CgBlendState.ALPHA`, `CgDepthState.TEST_WRITE`) or
> values already held by a `CgRenderState`. Only adoption mints records — about a dozen short-lived
> objects a few times per frame, against ~25 driver sync points per material bind removed.
>
> Implemented in `api/state/CgStateGroup.java` and `gl/state/CgGlStateManager.java`; 25 tests in
> `core/src/test/.../CgGlStateManagerTest.java`.

The original flat-field sketch is retained below for the record:

### The shadow (original sketch — not built)

Sixteen **groups**, reusing `CgGlSlot` as the group key — that decomposition is already validated and
matches how GL state is actually set and invalidated together.

Stored as **flat primitive fields** on one class, not an object graph. `apply` runs per material bind,
and this codebase already holds the line on hot-path allocation (`GLStateMirror`'s own javadoc states
it). Two long-lived instances exist — `current` and `baseline` — plus a small preallocated pool for
sub-scopes. `copyFrom(other)` is ~50 field assignments: nanoseconds, no garbage.

```java
final class CgGlStateShadow {
    // BLEND
    boolean blendEnabled; int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
    int blendEqRgb, blendEqAlpha;
    // DEPTH
    boolean depthTest, depthWrite; int depthFunc;
    // CULL
    boolean cullEnabled; int cullFace, frontFace;
    // STENCIL, COLOR_MASK, ALPHA_TEST, SCISSOR, VIEWPORT,
    // POLYGON_OFFSET, POLYGON_MODE, LINE_WIDTH, POINT_SIZE …
    // PROGRAM
    int programId; CallFamily programFamily;
    // FBO
    int drawFbo, readFbo; CallFamily fboFamily;
    // TEXTURES
    int activeUnit; final int[] bound2D = new int[MAX_UNITS];
    // VERTEX_INPUT
    int vao, arrayBuffer, elementArrayBuffer;

    int unknownMask;                       // bit per CgGlSlot.ordinal()

    void copyFrom(CgGlStateShadow o) { … }
}
```

~~`CallFamily` stays exactly here~~ — **superseded.** The rule that *an FBO bound via one family must be
unbound via the same family* does survive the mirror's death, but the shipped design gets it without the
enum: `fboChanged` derives the family from the **bind target** (`GL_FRAMEBUFFER_EXT` self-identifies, and
Core and ARB share an object namespace). No family is threaded through any call. `CallFamily` is deleted.

### Dirty semantics — two states, one invariant

One bit per group in `unknownMask`:

| Bit | Meaning | Effect on next `apply` |
|---|---|---|
| clear | shadow matches GL | **diff** — write only fields that changed |
| set | shadow may not match GL | **write unconditionally**, then clear the bit |

This is Iris's `setUnknownState()`, which is the one pattern in the old research worth copying
verbatim.

> **Invariant: after `adopt()`, `unknownMask == 0`.** The provider contract is *total* — it must
> populate every group. There is no case where a provider genuinely cannot determine state, because
> `glGet` is always available as the last resort. Making `adopt` total removes an otherwise nasty edge
> case: a group left unknown at adopt has **no valid baseline**, so `release` could neither restore it
> nor safely leave it. Unknown bits are therefore produced *only* by Tier-2 `invalidate()`, never by
> `adopt`.

### The four operations

**1. `apply` — Tier 3, the hot path.** Takes the existing immutable records, which already carry
presets (`CgBlendState.ALPHA`, `CgDepthState.TEST_WRITE`, …):

```java
void apply(CgRenderState state);      // fans out to the non-null sub-states
void apply(CgBlendState b);
void apply(CgDepthState d);
void apply(CgCullState c);
void apply(CgStencilState s);
void apply(CgAlphaState a);
void apply(CgColorMask m);
```

Each is: if the group's unknown bit is set → write everything, clear the bit. Otherwise compare field
by field and issue only what differs. `CgRenderState.apply()` becomes `mgr.apply(this)`.

**2. `CgGlState.save(slots…)` — Tiers 0 and 1, and it is the *existing* API, kept verbatim.**

> **Design revised 2026-07-30.** An earlier revision of this plan split this into `enterBurst()` and
> `push()`, and demoted `CgGlState.save()` to a deprecated shim. That was wrong, inherited from the
> framing of two stale documents rather than from any defect in the API. `CgGlState` /
> `CgGlScope` / `CgGlSlot` is a good API — declarative about what will be disturbed, exception-safe,
> nestable, zero-cost when empty. **Only the `glGet` inside `capture()` was ever the problem.** So it
> stays as the public surface and `CgGlStateManager` becomes the engine behind it. Nothing is
> deprecated; CrystalGUI and `CgShader.bindScoped()` never change.

```java
try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.VIEWPORT)) {
    …
}   // restored on close
```

Unchanged signature, unchanged idiom. What changes is underneath — and it absorbs the Tier 0 / Tier 1
distinction **automatically, by nesting depth**:

| Invocation | Behaviour |
|---|---|
| **outermost** | Tier 0 — `provider.adopt(...)` pours truth for the named groups, then snapshot |
| **nested** | snapshot the shadow for those groups — **zero GL reads** |
| **close (either)** | Tier 1 — diff against the snapshot, write back only what differs |

This is strictly better than making callers declare it. The earlier split pushed a question onto every
call site — *am I inside a pass?* — which this document previously had to flag as a hazard
("getting it backwards means restoring against a baseline that was never adopted"). Depth-driven
resolution makes the question self-answering, and the ambiguous sites resolve **correctly and
automatically**:

- Both pipeline passes and `CgUiPaintContext.beginFrame` are outermost → adopt.
- `CgUiPaintContext`'s three layer-FBO scopes are nested → snapshot, no reads.
- `CgFrameBuffer.blitFrom` — the site that could not be classified statically — is right either way.
  Verified: `CgRenderPipeline.blitDepthSnapshot` (`:293`, `:342`) runs *before* each pass's scope
  (`:314`, `:344`), so its `save(FBO)` is genuinely outermost and adopts. Which is the safe answer,
  reached without an annotation.

> **Refinement available:** because the depth blit sits outside the pass scope, each pass currently
> pays two adopts instead of one. Moving `blitDepthSnapshot` inside the scope is a one-line change that
> removes one pour per pass. Not required for correctness — noted so it is not mistaken for a bug.

**Lazy per-group adoption closes the one hole.** A nested `save(BLEND)` inside an outer `save(FBO)`
would otherwise snapshot a `BLEND` the burst never adopted. So adoption is **per group, on demand**:
`save()` adopts any named group not yet validated in the current outermost scope.

That collapses into the dirty bit rather than adding state — **`unknownMask` *is* "not validated in
this scope"**, so one bit per group serves invalidation, adoption tracking and diff-eligibility alike.
It also removes any need for callers to declare a group mask up front.

Snapshots are pooled in a fixed array, following the `ScissorStack` precedent — bounded depth, no
allocation.

**3. `invalidate` — Tier 2.** Zero GL calls; sets bits. The one genuinely new verb, because it does not
fit the scope idiom: it marks state untrustworthy without establishing a restore point.

```java
void invalidate(CgGlSlot... groups);
void invalidateAll();
```

Used at `initContext` / `onResize` / asset reload, and around `CgPreDrawHook` and
`CgLifecycleListener` dispatch.

### The provider contract

> **Correction to an earlier claim in this document.** An earlier revision said mc1201 pours at
> "zero `glGet`". **That is wrong** — verified against the extracted 1.20.1/1.20.4 sources below.
> Blaze3D's `GlStateManager` is a *partial* mirror and several groups are pure pass-through. The real
> numbers are in the fidelity table further down.

Providers differ in *which* groups they can supply cheaply, but the adopt invariant demands all of
them. Reconcile with a **template-method base class** rather than provider discipline — so totality is
structural, and a provider that forgets a group degrades to `glGet` instead of leaving a hole:

```java
public abstract class CgGlStateProvider {

    /** Total by construction: every group is filled, cheaply where possible. */
    public final void adopt(CgGlStateShadow into, int groupMask) {
        if (has(groupMask, BLEND))   readBlend(into);
        if (has(groupMask, DEPTH))   readDepth(into);
        …
        into.unknownMask = 0;                    // the invariant, enforced here
    }

    // Each defaults to a glGet read. Platforms override only what they can get cheaper.
    protected void readBlend(CgGlStateShadow s) { /* glGet fallback */ }
    protected void readDepth(CgGlStateShadow s) { /* glGet fallback */ }
    …
}
```

The `groupMask` matters: only the groups a scope actually names get adopted — which is exactly the slot
list `CgGlState.save(slots…)` already carries. A `doBind`-shaped scope has no business paying to adopt
`POINT_SIZE`. With lazy per-group adoption the mask is derived, never declared separately: whatever a
nested scope names and the outermost scope has not yet validated gets adopted on demand.

#### mc1201 — Blaze3D `GlStateManager` (verified against extracted 1.20.1 + 1.20.4 sources)

**Field names are identical across 1.20.1 Forge, 1.20.1 Fabric and 1.20.4 NeoForge, and are
unobfuscated** — `com.mojang.blaze3d` is never remapped, so one access layer covers all three with no
mapping dependency. That is the single best thing about this target.

| Group | Tracked? | How to read |
|---|---|---|
| VIEWPORT | ✅ | **`GlStateManager.Viewport` is a `public static enum` with `public static x()/y()/width()/height()`** — the one group needing neither reflection nor `glGet` |
| BLEND | ⚠️ partial | `BLEND.srcRgb/dstRgb/srcAlpha/dstAlpha` — **no blend equation exists in `BlendState`** |
| DEPTH | ✅ | `DEPTH.mask`, `DEPTH.func`, `DEPTH.mode.enabled` |
| CULL | ⚠️ partial | `CULL.mode`, `CULL.enable.enabled` — **no `frontFace`** |
| STENCIL | ✅ | `STENCIL.func{func,ref,mask}`, `.mask`, `.fail`, `.zfail`, `.zpass` |
| COLOR_MASK | ✅ | `COLOR_MASK.red/green/blue/alpha` (plain public fields) |
| SCISSOR | ⚠️ enable only | `ScissorState` holds **only** `mode` — **the box is not tracked** |
| POLYGON_OFFSET | ⚠️ partial | `POLY_OFFSET.fill/line/factor/units` — **no `point`** |
| TEXTURES | ⚠️ **first 12 only** | `TEXTURES[i].binding`; active unit has a public getter — **but see the unit-count trap below** |
| PROGRAM | ❌ | `_glUseProgram` is pass-through — straight to `GL20.glUseProgram` |
| FBO | ❌ | `_glBindFramebuffer` is pass-through |
| VERTEX_INPUT | ❌ | `_glBindVertexArray` / `_glBindBuffer` both pass-through |
| POLYGON_MODE | ❌ | `_polygonMode` is pass-through |
| LINE_WIDTH · POINT_SIZE | ❌ | not tracked |
| ALPHA_TEST | n/a | core profile — correctly a no-op |

**Access strategy: cached reflection, not mixin accessors.** All nine holder fields are
`private static final`; the nested classes are package-private (so their `public` fields cannot be
named from our package); and `BooleanState.enabled` is **private with no getter**, so every enable
flag needs it. Iris solves this with a mixin accessor — we should not, for three reasons:

1. `AGENTS.md` already sets mixins as last resort, and reflection is a real alternative here.
2. It is 3–4 reads per frame. `VarHandle`s cached at init make performance irrelevant either way.
3. **Decisive:** a reflection miss can `catch` and fall back to the `glGet` path for that group, so a
   future mapping or field-name change degrades *performance*. A mixin `@Accessor` miss is a hard
   crash at class-load. For a library spanning 1.20.1 and 1.20.4 that resilience is worth more than
   the nanoseconds.

Residual `glGet` per adopt: PROGRAM (1) + FBO (2) + VERTEX_INPUT (3) + blend equation (2) +
frontFace (1) + scissor box (1) + polygon mode (1) + line width (1) + point size (1) ≈ **13** — but
only for the groups a given burst actually declares. A `doBind`-shaped burst pays **3**.

#### mc1710 + Angelica — the best source of all, and the session's biggest surprise

**Zero `glGet`, zero reflection.** Angelica's `GLStateManager` (3871 lines) annotates its
`protected static final` state holders with Lombok `@Getter`, and Lombok defaults to
`AccessLevel.PUBLIC` — verified: no `lombok.config` anywhere in the repo, and zero
`@Getter(AccessLevel…)` overrides. So `getBlendState()`, `getDepthState()`, `getColorMask()`,
`getCullState()`, `getAlphaState()`, `getTextures()`, `getScissorTest()`, `getStencilTest()`,
`getLineState()`, `getPolygonOffset*State()` are all **public static**.

Two structural gifts:

- **`BlendStateStack extends BlendState`** — the stack object *is* the current state. No unwrapping;
  `getBlendState().getSrcRgb()` is the live value, with push/pop copying to an internal array.
- The state classes are `@Getter`/`@Setter` (`BlendState`, `DepthState`, `AlphaState`, `StencilState`)
  or have plain `public` fields (`ColorMask`, `ViewportState`).

Coverage is **materially richer than Blaze3D's** — including `alphaState` + `alphaTest` (which
1.7.10 genuinely has and 1.20.x does not), `viewportState`, `lineState`, `pointState`, `polygonState`,
and all three `polygonOffset{Point,Line,Fill}State` enables that Blaze3D omits.

> **The irony worth recording: the oldest target has the best state source, precisely because
> Angelica did the full-redirect work we just decided not to do.** We get its benefit by *reading* its
> mirror instead of competing with it — which is also why scrapping our redirector is not a
> capability loss on this platform. It is a capability *gain*, since Angelica's mirror is far more
> complete than ours ever was.

Verified against a local checkout at **`X:\projects\CrystalGraphics\research_repos\angelica`** —
note that is a *different* repository root from this one, not `CrystalGUI/research_repos/`. Key file:
`src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java`, with the state classes under
`glsm/states/` and the stacks under `glsm/stacks/`.

Two implementation notes: `IStateStack` lives in **GTNHLib**
(`com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack`), a published artifact, so this
compiles against real types rather than reflection. And Angelica registers
`com.mitchej123.glsm.GLStateManagerService` through `META-INF/services` — worth investigating as an
*implementor-agnostic* `ServiceLoader` path, which would also cover any future GLSM implementor.
Reflect-with-fallback regardless, since Angelica is an optional dependency.

#### mc1710 vanilla · harness — batched `glGet`

No host mirror exists. Full sweep, ~40 reads, 3–4× per frame. This is the honest floor, and it is
still **far** below `25 × material binds`.

#### 1.12.2 and other future targets — unverified

Forge 1.12.2's `GlStateManager` is the same *shape* as Blaze3D's (partial mirror, private static
holders) and would follow the mc1201 recipe. **Not verified — no sources checked out.** Do not treat
the mc1201 field list as transferable; re-derive it against real 1.12.2 sources before writing that
provider.

### The texture-unit trap — TEXTURES adoption is unsound if written naively

Three different unit counts are live in the codebase right now:

| Source | Count |
|---|---|
| Blaze3D `GlStateManager.TEXTURE_COUNT` | **12** |
| `GLStateMirror.MAX_TEXTURE_UNITS` | **32**, hardcoded |
| `CgGlStates.TextureState` | `CgCapabilities.getMaxTextureUnits()` — hardware, 32–192 |

And the trap: **`CgBindingPoints` allocates CrystalGraphics' own units by counting *down* from the
maximum** —

```java
OBJECT_DATA         = new Binding(--maxSsboBindings, --maxTextureUnits);
QUAD_RENDERER       = new Binding(--maxSsboBindings, --maxTextureUnits);
DEPTH_TEXTURE_UNIT  = --maxTextureUnits;
```

So CrystalGraphics deliberately occupies the **highest** units while Blaze3D tracks only the
**lowest 12**. Adopting TEXTURES from `GlStateManager` would therefore miss precisely the units
CrystalGraphics uses — the failure mode being textures that are intermittently wrong, with no
exception anywhere.

**Resolution: split by ownership, not by index.** Units 0–11 are MC's and get adopted; the high units
are *exclusively* CrystalGraphics' own, so they need no adoption at all — we always know what we put
there. That is the same exclusive-ownership argument the old research got right in its §2.8, applied
to a case it never considered. Write the group as "adopt MC's range, self-track ours," and never as a
single loop over `getMaxTextureUnits()`.

### Fidelity summary (corrected)

| Platform | `glGet` per adopt | Reflection | Notes |
|---|---|---|---|
| mc1710 + Angelica | **0** | **none** | richest mirror; public Lombok getters |
| mc1201 ×3 (1.20.1/1.20.4) | ~13 full, **3** for a `doBind` burst | yes, cached + fallback | unobfuscated names, one layer for all three |
| mc1710 vanilla | ~40 | none | no host mirror |
| harness | ~40 | none | no host mirror |
| 1.12.2 (future) | unverified | presumed yes | re-derive before implementing |

### Testability — the part that makes this safe to refactor

`CgGL` dispatches through `CgGLBackend`, injected by `CgGL.init(backend)`. So the entire manager is
**unit-testable with no GL context**: install a recording backend, drive the manager, assert the exact
call sequence.

> **Update 2026-07-30 — step 1 did not need this at all.** Because emission lives on the value type
> (`CgStateGroup.emit()`) rather than in the manager, a **five-line fake group** records its own
> emissions and exercises the manager completely with no GL backend whatsoever. All 25 manager tests run
> that way. The recorder below is still wanted eventually — to verify the *real* groups emit the right GL
> calls — but that is a different question from whether the manager *decides* correctly, and it is no
> longer a step-1 prerequisite. Another dividend of putting `emit()` on the record.

> **Cost, stated honestly:** `CgGLBackend` is an **abstract class** with **141 abstract methods** (of
> 143 total), so `CgRecordingGLBackend` must implement all 141. Mechanical but not free — budget it
> into the step that tests real group emission.
>
> Only **29** are the state setters worth recording (`glEnable`/`glDisable`/`glBlend*`/`glDepth*`/
> `glCull*`/`glFrontFace`/`glStencil*`/`glColorMask`/`glScissor`/`glViewport`/`glPolygon*`/
> `glLineWidth`/`glPointSize`/`glAlphaFunc`/`glUseProgram`/`glBindFramebuffer`/`glActiveTexture`/
> `glBindTexture`/`glBindBuffer`/`glBindVertexArray`). Have the other 112 throw
> `UnsupportedOperationException` rather than no-op — a state-manager test that reaches an unexpected
> GL call should fail loudly, not silently pass. Do **not** give the production abstract class no-op
> default bodies to shrink this; a GL method that silently does nothing is a far worse hazard than
> test boilerplate.

```java
CgGL.init(recorder);
mgr.apply(CgDepthState.TEST_WRITE);
mgr.apply(CgDepthState.TEST_WRITE);            // identical
assertEquals(1, recorder.countOf("glDepthFunc"));   // second call deduped
mgr.invalidate(CgGlSlot.DEPTH);
mgr.apply(CgDepthState.TEST_WRITE);
assertEquals(2, recorder.countOf("glDepthFunc"));   // unknown forces re-issue
```

That converts "did the dedup break?" from a visual-inspection question into an assertion, which
matters because **a state manager that wrongly skips a call produces wrong rendering, not a crash.**
These tests belong in `core/src/test/` — no GL, no CrystalGraphics-absent constraint.

### Profiling

`glstate.callsIssued`, `glstate.callsSkipped`, `glstate.adopt`, `glstate.invalidate` via `CgProfiler`.
`callsSkipped / (issued + skipped)` is the dedup hit rate, and it is the number that proves the
design is doing anything at all.

### Two mismatches found while specifying this

Both are real, and both are now **resolved** — see [D2](#d2-cgcullstate-gains-frontface-no-separate-manager-setter)
and [D3](#d3-color_mask-tracks-8-targets-always--1-expands-at-the-entry-point). Recorded here as the
discovery, with the resolution kept in one place:

- **`CgCullState` is `(enabled, face)` — no `frontFace`**, yet `CgGlStates.CullState` captures and
  restores `glFrontFace`, so the shadow must hold it. → **D2:** widen the record, 2-arg convenience
  constructor defaults to `CgGL.GL_CCW` (verified present, `0x0901`).
- **`CgColorMask` carries a `targetIndex`** (`-1` = all targets) and `CgRenderState` holds a *list*, for
  MRT — so COLOR_MASK is not four booleans. → **D3:** track 8 targets always, expand `-1` at the entry
  point, keep the diff uniform. `CgGL.glColorMaski` is verified present on both `CgGL` and `CgGLBackend`.

---

## Component inventory

### New — `core/src/main/java/com/crystalgraphics/gl/state/`

| Class | Role |
|---|---|
| `CgGlStateManager` | The **engine**, not the public surface — `CgGlState` stays that. Owns the shadow, the scope stack, and the provider; exposes `apply(...)`, `invalidate(groups…)`, and the enter/exit hooks `CgGlState.save()` drives. |
| `CgGlStateShadow` | The plain-data shadow: flat primitive fields for all 16 `CgGlSlot` groups plus `unknownMask`. No GL calls, no hot-path allocation. Doubles as the baseline and as sub-scope snapshots via `copyFrom`. |
> **No new scope types.** `CgGlScope` already is the `AutoCloseable` restore point and is kept; it
> simply stops holding `SlotState[]` and starts holding a pooled shadow snapshot. `CgGlSlot` is kept
> verbatim and doubles as the manager's group key.

> No separate `CgGlStateSnapshot` type: a snapshot *is* a `CgGlStateShadow`, which is what makes
> `copyFrom` serve baseline, sub-scope and pool duty alike.

### New — `platform/`

| Class | Role |
|---|---|
| `CgGlStateProvider` | The `adopt(CgGlStateShadow)` SPI above. |

### New — per loader

`Mc1201GlStateProvider` (in `mc1201/common/`), `Lwjgl2GlStateProvider` (in `mc1710/`, with the
Angelica-shadow path and the vanilla `glGet` fallback), and a `glGet`-based default the harness uses.

### Deleted

**`core/gl/state/`** — `GLStateMirror` · `CgGlStates` (the 16 `glGet` capture classes) · `SlotState` ·
`-Dcrystalgraphics.boundary.forceGlGet`.

> **`CgGlState`, `CgGlScope` and `CgGlSlot` are NOT deleted** — they are the public API and they stay,
> with new implementations. An earlier revision of this plan listed them here; that was wrong. See
> [blocker §2](#2--two-repo-breaking-change--dissolved-by-not-deleting-the-api).

**`mc1710/mc/coremod/`** — the whole package: `CrystalGLRedirects` · `CrystalGraphicsTransformer` ·
`CoverageMatrix` · `CrystalGraphicsCoremod`. Plus the `FMLCorePlugin` manifest attribute, and the
`-Dcrystalgraphics.redirector.*` flags (`disable`, `verbose`, `verbosePrefix`, `forceAngelica` —
the last one relocating with detection). Update `docs/` and root `AGENTS.md`, both of which currently
document the coremod and the redirect layer as live architecture.

**Relocated, not deleted:** Angelica detection (`isAngelicaPresent`) → an ordinary runtime classpath
probe, consumed by the 1.7.10 `CgGlStateProvider`.

**Rewritten, not deleted:** the FBO-tracking assertion in `CrystalGraphicsIntegrationTest:129–150`
must be re-pointed at the new shadow rather than dropped.

> ⛔ **This callout is WRONG and was acted on twice. `CallFamily` is deleted (2026-07-31).** Every "user"
> below is a `protected CallFamily callFamily() { return CONSTANT; }` override of an abstract method with
> **zero callers** — its consumer `CrossApiTransition` was already deleted. The correct home for the concept
> is `CgCapabilities.FramebufferPath`, which already existed. Original text follows.
>
> ~~**`CallFamily` stays — do not delete it.**~~ It looks like part of the mirror because
> `GLStateMirror` tracks a family per binding, but it is independently load-bearing for the
> **Core/ARB/EXT waterfall**: nine files outside `gl/state/` use it, including
> `CgCoreFrameBuffer` / `CgArbFrameBuffer` / `CgExtFrameBuffer`, `CgAbstractShaderProgram` and its
> two subclasses, and `CgGLBackend` in `platform/`. Only the *mirror's* per-binding family tracking
> (`getCurrentFboFamily()` / `getCurrentProgramFamily()`, and the family-mismatch unbind dance in
> `FboState.restore()` / `ProgramState.restore()`) goes away.
>
> That restore dance is worth understanding before deleting it rather than after: it exists because
> an FBO bound via one family must be unbound via the same family. The new manager still needs that
> rule — it just gets the family from the binding call itself instead of from a mirror lookup.

### Changed

| File | Change |
|---|---|
| `CgMaterial` | Delete `stateScope` field, the `doBind.stateSave` block, and its close in `unbind`. Replace `getPassRenderState(variant).apply()` with a manager-routed apply. |
| `CgRenderPipeline` | **no change** — `:314` / `:344` already call `CgGlState.save(...)` and resolve as outermost. Optional one-line refinement: move `blitDepthSnapshot` inside the scope to save a pour. |
| `CgUiPaintContext` (CrystalGUI) | **no change** — `:244` resolves outermost, the layer scopes (`:591`, `:621`, `:700`) resolve nested. This is the point of keeping the API. |
| `CgGraphicsLifecycle` | `invalidate()` at `initContext` / `onResize`; wrap `CgLifecycleListener` dispatch in `invalidate()`. The only genuinely new call sites. |
| `CgRenderState` + `CgBlendState`/`CgDepthState`/`CgCullState`/`CgStencilState`/`CgAlphaState`/`CgColorMask` | `apply()` / `clear()` route through the manager instead of calling `CgGL` directly. Records stay immutable — only the *application* moves. |
| `CgFrameBuffer` (×3), `CgDebugBlit` (×2), `CgShaderImpl` (×2), `CgFontDemo` | **no change** — each resolves adopt-or-snapshot by nesting depth at runtime. This is what removes the per-site classification hazard the earlier design carried. |

---

## Implementation order

Staged so each step is independently verifiable and nothing is half-migrated across a release
boundary.

> **Reordered 2026-07-30.** The old order deleted the per-bind save at step 3, before bursts existed.
> That is unsafe — see [blocker §4](#4--null-slot-semantics--found-late-resolved-by-reordering):
> null render-state slots mean "inherit the pass baseline", and without a baseline to diff against,
> a material declaring no `Blend` block would silently draw with the *previous material's* blend.
> The deletion now follows the burst machinery instead of preceding it.

1. **`CgGlStateShadow` + `CgGlStateManager`, diffing, no boundaries yet.** Adopt via `glGet` at
   construction only. Pure unit-testable logic against a recording backend — assert that a repeated
   `apply` of the same state issues zero GL calls, that a changed field issues exactly one, and that
   `invalidate` forces a full re-issue.
2. **Re-back `CgGlState.save(...)` / `CgGlScope` onto the manager, signatures unchanged.** The keystone
   step, and the one that makes everything else cheap. Implement depth-driven adopt-or-snapshot and
   lazy per-group adoption here. `CgShader.bindScoped()` and **CrystalGUI compile untouched**; all ~12
   existing save sites keep working and stop issuing `glGet` without being edited at all. After this
   there is only ever *one* system, which is what dissolves the coexistence hazard.
   > Land it with restore-by-diff but adoption still `glGet`-backed (no provider yet). Behaviour is
   > preserved, the `glGet` count drops from per-capture to per-outermost-scope, and no call site moves.
3. **Migrate `CgRenderState.apply()` through the manager, and implement null-slot = restore-to-baseline.**
   Delete the dead `clear()`. This is the correctness precondition for step 5, not a cosmetic
   migration — the null-slot rule is what preserves today's observable behaviour.
4. **Verify the boundaries behave as intended** — the two pipeline passes and
   `CgUiPaintContext.beginFrame` resolve as outermost (adopt), the three layer-FBO scopes as nested
   (snapshot). This is now a *verification* step rather than an implementation one, because step 2's
   depth-driven rule already produces it. Assert it with the recording backend rather than by
   inspection. Optionally take the one-line refinement moving `blitDepthSnapshot` inside the pass scope
   to save one pour per pass.
5. **Delete `CgMaterial`'s per-bind save.** Where the 347 ms goes away. Safe only now, because the
   outermost-scope baseline exists for null slots to fall back to. Measure against the recorded
   `text-3d` baseline **in isolation** before continuing — the single highest-value step.
6. **Add `invalidate()` at the non-scope boundaries** — `initContext`, `onResize`, asset reload. These
   are the Tier 0 sites that are *not* scopes and therefore are not covered by step 2.
7. **`CgGlStateProvider` base class, `glGet` fallback for every group.** Zero platform-specific code,
   and it immediately and correctly serves **both** the harness and vanilla 1.7.10. Proves the SPI and
   the totality invariant on their own.
8. **Angelica override for mc1710.** Deliberately the *first* real provider: zero `glGet`, zero
   reflection, no mixins, and the richest coverage of any target (see the provider section). It is
   simultaneously the easiest override to write and the biggest fidelity win — so it validates the
   template-method design before anything harder leans on it.
9. **Blaze3D override for mc1201 ×3.** The hard one: cached reflection with per-group `glGet`
   fallback, the *partial*-mirror gaps (blend equation, front face, scissor box, polygon mode), and the
   texture-unit ownership split. One implementation covers 1.20.1 Forge, 1.20.1 Fabric and 1.20.4
   NeoForge — field names are identical and unobfuscated.
10. **Tier 2 invalidation** at the hook / listener / `OpenGlHelper` sites.
11. **Add `assertOnRenderThread()`** to the manager's mutating surface — the one still-valid gap the
    older research identified (§5.2). Cheap, and a state manager is more sensitive to off-thread calls
    than a stateless facade: a bad call silently corrupts the shadow instead of just failing.
12. **Delete `CgGlStates`, `SlotState` and the `forceGlGet` flag** — the `glGet` capture machinery, now
    unreferenced. `CgGlState`, `CgGlScope` and `CgGlSlot` **survive**; only their old innards go.
13. **Delete the 1.7.10 coremod**, relocating Angelica detection to a runtime classpath probe first.
    Deliberately last: it is the highest-blast-radius change, it is not needed for any of the
    performance win, and it must not be entangled with a step that has to be measured.

**Step 2 is the keystone** — after it there is one system rather than two, and the migration is
single-repo. **Step 5 delivers essentially all of the measured `doBind` win**; step 6 is a second,
independent `glGet` reduction. Steps 7–10 make it *correct* on every platform rather than just fast on
one. Steps 11–13 are cleanup and can slip indefinitely — step 12 deletes only the `glGet` capture
innards (`CgGlStates`, `SlotState`), not the API.

> **Note how few call sites move.** Steps 2–5 touch `CgGlState`'s implementation, `CgRenderState`, and
> `CgMaterial`. Every other existing `CgGlState.save(...)` site — `CgRenderPipeline`, `CgUiPaintContext`,
> `CgFrameBuffer`, `CgDebugBlit`, `CgShaderImpl`, `CgFontDemo` — is **unedited** and gets faster anyway.
> That is the payoff for keeping the API instead of replacing it.

> **Ordering constraints.**
> - **Step 5 must not precede step 4.** The per-bind save cannot be deleted before bursts exist, or
>   null render-state slots lose the baseline they inherit from — see blocker §4.
> - **Step 13 must not precede step 8.** The Angelica provider is what makes Angelica detection
>   load-bearing again; deleting the coremod while `isGapOnlyMode` has no replacement consumer would
>   strand it.

---

## Decisions taken — step 1 is unblocked

Resolved 2026-07-30 so implementation can start without stopping to ask. Each was a real fork.

### D1. The shadow **tracks** all 16 groups; **dedup** initially covers only the state groups

The question I had not asked: all four *binding* groups already have established write paths of their
own — `CgAbstractShaderProgram.bind()` → `CgGL.glUseProgram` (`:142`), `CgFrameBuffer.bind()` (`:385`),
`CgVertexArray.bind(int)` (`:164`), and `CgTexture.bind(unit)`. Routing four public APIs through the
manager is a far wider refactor than the one being measured, and it is not where the 347 ms lives.

| Group class | Write path | Dedup now? |
|---|---|---|
| State — `BLEND` `DEPTH` `CULL` `STENCIL` `ALPHA_TEST` `COLOR_MASK` `SCISSOR` `VIEWPORT` `POLYGON_*` `LINE_WIDTH` `POINT_SIZE` | routed **through** the manager | ✅ yes |
| Binding — `PROGRAM` `FBO` `VERTEX_INPUT` `TEXTURES` | keep their own API; **notify** the manager on write | ❌ follow-up |

**Notify-on-write is not optional for the binding groups.** If `CgFrameBuffer.bind()` writes GL without
telling the shadow, the shadow goes stale and a later restore writes a *wrong* value — strictly worse
than not tracking it at all. One field assignment per bind; no dedup decision moves.

This keeps steps 1–5 scoped to the measured problem. Binding dedup — where Angelica gets much of its
win — becomes a labelled follow-up rather than a prerequisite.

### D2. `CgCullState` gains `frontFace`; no separate manager setter

`CgGlStates.CullState` already captures and restores `glFrontFace`, so the shadow must hold it, and a
manager-only setter would let the record and the shadow disagree about what "cull state" means.

**Decision:** widen to `CgCullState(boolean enabled, int face, int frontFace)` plus a 2-arg convenience
constructor defaulting `frontFace` to CCW, so the `NONE`/`BACK`/`FRONT` presets and any existing 2-arg
call sites keep compiling.

### D3. COLOR_MASK tracks **8 targets always**; `-1` expands at the entry point

`targetIndex` is `-1` (all targets, `glColorMask`) or `0..7` (per-target, `glColorMaski`), and
`CgRenderState` holds a *list*.

**Decision:** the shadow holds 8 per-target masks unconditionally. `apply()` with `-1` writes all 8
shadow entries and issues one `glColorMask`; `0..7` writes one and issues `glColorMaski`. The **diff
stays uniform over 8 entries**, so the all-versus-specific distinction lives only at the entry point and
never in the comparison. That is what turns the fiddliest group into an ordinary one.

### D4. The manager is an ordinary instantiable class, not a static holder

`GLStateMirror` was all-static, which is exactly why it was untestable and needed a `markUnknown()`
nobody ever called.

**Decision:** `CgGlStateManager` has **no static state**. One process-wide instance is held by
`CgGlState`; unit tests construct their own against a recording backend. No `resetForTest()`, no
leakage between tests, no static-init ordering hazard.

### D5. Scope stack depth 16, fail fast on overflow

Matches the `ScissorStack` precedent and covers observed nesting (`beginFrame` → layer → nested layer).
Fixed preallocated array, no allocation. **Throw** on overflow rather than silently dropping — a
dropped scope is an unrestorable state leak, and this project's philosophy is fail-fast.

### D6. The TEXTURES ownership boundary is **provider-declared**, not hardcoded

Blaze3D tracks 12; Angelica's `TextureUnitArray` has its own size; CrystalGraphics allocates *downward*
from the hardware max (see [the texture-unit trap](#the-texture-unit-trap--textures-adoption-is-unsound-if-written-naively)).

**Decision:** the provider declares `trackedTextureUnits()`; the manager adopts `0 .. n-1` and
self-tracks everything above. No constant `12` anywhere in `core/`.

### D7. `unknownMask` starts fully set

At construction the manager knows nothing. Obvious, but it is the invariant that makes the first `apply`
on a fresh context correct by default instead of dependent on an adopt having happened first.

### D8. `ALPHA_TEST` stays tracked and no-ops on core profile

Exactly what `CgGlStates.AlphaTestState` already does. 1.7.10 has alpha test and Angelica tracks it;
1.20.x is core profile where `glEnable(GL_ALPHA_TEST)` is an error. Keep the group, gate the writes —
deleting the group would silently lose state on the 1.7.10 path.

---

## ⚠️ Corrections from implementation — read before trusting anything below

Four conclusions in this document were **wrong**, and each was found only by a visual regression in the
harness rather than by a test. Recorded first because the sections further down still read as confident.

### C1. Tier 0 must adopt **unconditionally** at an outermost scope

The single most important rule, and the one whose absence broke UI text.

Adoption was implemented as lazy *everywhere*: "pour truth only if the slot is not already trusted."
That is right for a **nested** scope and wrong for an **outermost** one. Between two outermost scopes,
control was outside CrystalGraphics — Minecraft, another mod, or the debug harness — and any of them may
write state through an API we cannot observe. **Trust does not survive leaving our control, however
recently it was established.**

The concrete failure: `gl-debug-harness/util/GlStateResetHelper.java:64` calls raw
`GL11.glDisable(GL_BLEND)` after every frame. The shadow still read "blend enabled, alpha func", the
outermost save at `CgUiPaintContext.beginFrame` believed it and skipped the re-read, and the next
`apply(CgBlendState.ALPHA)` was eliminated as redundant. Blending was never re-enabled, so glyphs and
SDF corners rendered fully opaque. **The first few frames looked correct** — nothing was trusted yet —
which made it read like an intermittent fault rather than a latch.

> Verify-mode baseline before the fix, over one gallery run: **CULL 84,523 · BLEND 1,360 · DEPTH 1,358 ·
> TEXTURES 1,349** stale-shadow reports. All four are slots `beginFrame` saves.

### C2. Blocker §1 was **not** "dissolved" — notify-on-write was mandatory

§1 below claims the coexistence hazard dissolved because `CgGlState.save()` routes through the manager.
That reasoning covers **scoped save/restore only**. It says nothing about the many code paths that write
state directly, and D1 had already mandated notify-on-write for exactly that reason — then it was not
implemented. Two consequences had to be fixed afterwards:

- **`apply()`/`emit()` were inverted** on the five state records: `emit()` is raw GL, `apply()` routes
  through the manager. This silently corrected **twelve direct bypasses** (`CgDebugBlit` and all three
  pipeline renderers were calling `CgDepthState.NONE.apply()` and friends straight to GL).
- **`notifyForeignWrite(slot)` added and wired at 36 sites** — program bind/unbind, VAO bind, 22 texture
  binds, 12 framebuffer binds. It marks a domain *untrusted* rather than recording a value: one bitmask
  write, no allocation, and it cannot itself be wrong.

### C3. A null render-state slot means **leave alone**, not "revert to the scope baseline"

Blocker §4 below argued the opposite, and implementing it broke text rendering independently of C1.

Tracing the old code settles it: for a null slot the per-bind scope was a **no-op** — `apply()` issued
nothing and `close()` restored the same value. Null therefore meant *leave alone*. And callers
legitimately configure ambient state **inside** a scope (`CgUiPaintContext` enables blending for UI
drawing after opening its own scope), so the baseline is precisely what an undeclared domain must *not*
inherit. What the per-bind scope actually protected was **declared** domains.

### C4. `CgMaterial`'s per-bind scope cannot exist at all — bind/unbind is not LIFO

Deleting it was right, but for a second reason this document never identified.
`CgQuadRenderer.useMaterial` calls `bind()` on **every** call and `unbind()` only when the material
changes, and UI painting opens nested FBO/viewport scopes while a material is bound. A stack-based scope
closed under those conditions throws *"closed out of order"* — which it duly did when briefly
reinstated. The old `glGet` version never crashed only because overwriting its single `stateScope` field
leaked the previous capture silently, restoring stale values or none at all.

### C5. `emit()` must fully establish its value, or the shadow lies

`CgCullState.NONE` carried `face = 0` and `emit()` never wrote it, so the shadow claimed `face=0` while
the driver still reported `GL_BACK` — 84,523 spurious staleness reports. Harmless for deduplication
(equal values emit identically) but it makes verification useless. `NONE` now carries `GL_BACK` and
`emit()` sets the mode even when culling is disabled.

### C6. The four binding domains are **excluded from deduplication** — D1 was right, I overrode it

D1 said: binding domains keep their own APIs and are *"dedup later"*. The generic `apply()` deduped them
anyway, and making that safe would require every path reaching GL through `CgShader.bind()`,
`CgFrameBuffer.bind()`, `CgTexture.bind(unit)` and `CgVertexArray.bind(id)` to report — forever, including
in code not yet written.

That was attempted and failed twice. Forty-six `notifyForeignWrite` calls across eight files, and the
verification pass still caught texture bindings arriving unannounced: the concrete classes in
`gl/texture/` were patched while **the base-class `CgTexture.bind` in `api/texture/` was missed**, so the
binds that mattered were invisible. The reported symptom was a shadow of all-zeros against a driver
holding texture 2 on unit 0 and texture 1 on unit 29.

**Resolution: never eliminate a call for a binding domain.** They are still tracked, snapshotted and
restored — only the skip is gone. That removes the failure class instead of chasing it. The cost is a few
redundant bind calls per scope close, and bind calls are cheap: unlike the `glGet` captures this whole
subsystem replaced, they do not synchronise with the driver. Every saving that motivated the work comes
from the pure-state domains, which are what `CgRenderState` applies on each material bind.

`notifyForeignWrite` is kept, now as belt-and-braces for diagnostics and verification rather than the
thing correctness rests on.

> **Reading verification output after this change:** binding domains will no longer appear at all, because
> verify only runs when a call is *about* to be eliminated. That is the decision being gone, **not**
> evidence the shadow became truthful for them. Any remaining report concerns a pure-state domain and is
> worth chasing.

### C7. Boundary coverage must be enumerated — three fixes were needed, not one

The same bug recurred three times because each fix addressed the instance in front of me instead of the
question *"where else can GL state change without us knowing?"* A shadow-based manager is only ever as
good as its boundary coverage, and the boundaries are not obvious from any single class.

| Where truth enters | Covers | Was missing |
|---|---|---|
| **Outermost `save()` adopts unconditionally** (C1) | any path that opens a scope | paths that open none |
| **`tickFrame()` invalidates everything** | between frames | mid-frame resets |
| **`GlStateResetHelper` notifies** | deliberate wholesale resets by first-party code | — |

Why each alone was insufficient:

- **Scope adoption** fixed CrystalGUI, because `CgUiPaintContext.beginFrame` happens to open a scope. It
  did nothing for `TextScene3D`, which calls `CgTextRenderer` directly — and once `CgMaterial`'s per-bind
  scope was removed (C4), **no scope existed on that path at all**, so the shadow was never corrected and
  a stale value persisted across every frame.
- **Frame-boundary invalidation** halved the staleness (2,256 → 1,119 reports) and no further, because
  `GlStateResetHelper.resetAfterScene()` runs *mid*-frame, between the scene and the overlays.
- **The harness notification** took it to 1 report in 1,132 frames — that one at frame 1, before any
  boundary had yet been crossed, self-corrected at the first one.

**Rule for anything added later:** code that resets GL state wholesale must call
`CgGlState.invalidateAllIfPresent()`. Raw GL is invisible to the manager by construction, and the failure
mode is a *missing* call, which renders wrongly and never throws.

**On diagnosing this class of bug:** the same symptom (opaque quads) had three distinct causes across this
work, and reasoning from the symptom produced a wrong answer every time. What worked, every time, was
`-Dcrystalgraphics.state.verify=true` — it names the domain and prints tracked-vs-actual. The MSDF/bitmap
split was especially misleading: it looked like a tier-specific bug, but both tiers had blending disabled
and only one *shows* it, because MSDF's near-binary alpha still discards cleanly.

### C8. Deleting the per-bind scope moved material isolation — it did not remove the need for it

Removing `CgMaterial`'s per-bind scope was necessary (C4: bind/unbind is not LIFO). But it also silently
transferred a responsibility nobody picked up, and the code comment left behind actively concealed it:

```java
// bind/draw/unbind — CgMaterial.doBind() owns render state save/apply/restore.   // NO LONGER TRUE
```

**Declared** domains are fine: `apply()` writes every domain a material declares, so it always overrides
the previous material. **Undeclared** domains were not. With the per-bind restore, a material inherited
the *pass ambient* value; without it, it inherits *the previous material's* value.

Concretely: `CgTransparentRenderer` sets `CgDepthState.TEST_ONLY` at pass start — ZWrite off, commented
"preserve opaque depth". `text.shader` declares `DepthWrite ON`. Text followed by any material declaring
no `Depth` block therefore **wrote depth during the transparent pass**, defeating that pass's explicit
purpose.

**Resolution: the pass renderers re-assert their ambient state before each batch**, rather than once per
pass. That restores exactly the starting point the per-bind scope used to guarantee, without a scope — so
it cannot hit the LIFO problem. It is nearly free because the manager eliminates the calls whenever the
previous batch left those domains alone, which is the common case within a pass.

Found in the same pass: `CgTransparentRenderer` closed with a raw `CgGL.glDepthMask(true)`, desyncing the
shadow. It was the lone `DEPTH` staleness report in the verification log; routed through the manager, that
report went to zero.

> **The transferable lesson:** when removing a mechanism, ask *who owns the responsibility now?* — not just
> *is removing it safe?* This one was surfaced by a reviewer's question ("is that okay?"), not by four
> rounds of debugging, because the deletion had been treated as settled.

### Two diagnostic flags now exist, and earned their keep

| Flag | Effect |
|---|---|
| `-Dcrystalgraphics.state.verify=true` | Before eliminating a call, re-read the domain and compare; log the domain plus tracked-vs-actual on mismatch, then emit anyway. **This is what found C1**, after two wrong guesses. |
| `-Dcrystalgraphics.state.noDedup=true` | Never eliminate a call. One run distinguishes "the shadow is lying" from "a semantic regression", which halved the search space. |

**The meta-lesson:** every one of these failures was a *missing* GL call, which renders incorrectly and
never throws. The unit tests — 802 of them, all passing throughout — could not catch any of it, because
they verify that the manager *decides* correctly, not that its picture of the world is true. Build the
verification tool early next time.

---

## Blockers — resolved 2026-07-30

All three original blockers are now closed, and closing them **changed the step order**. The keystone
is one decision, in §2 below: *adapt the old API onto the new manager instead of running two systems
side by side.* Two of the three blockers dissolve as a consequence rather than needing separate work.

### 1. ✅ Migration coexistence — dissolved by §2

The original worry, when the plan still assumed a parallel-systems migration: `CgGlScope.close()`
writes GL behind the manager's back, the shadow desyncs, and the manager starts skipping calls it
needed to issue — with the symptom being rendering that is wrong only *after* some unrelated path ran.

**This only exists if there are two systems.** Reimplementing `CgGlScope` / `CgGlState.save()` *over*
the manager (§2) means every restore already goes through the shadow, so it cannot desync. One system,
two front doors.

Residual rule, still worth stating: until the `CgGL` guard (§7) lands, any un-migrated code path that
writes state directly through `CgGL` must `invalidate()` its group. During migration the pragmatic
version is to have the facade `push`/`pop` invalidate conservatively rather than diff — correct-but-slow
first, dedup switched on once the call sites are all migrated.

### 2. ✅ Two-repo breaking change — dissolved by *not deleting the API*

The constraint that forced this: `CgGlScope bindScoped();` is a method on the **public `CgShader`
interface**, `CgGlScope` has leaked into `api/` (`CgMaterial`, `CgRenderPipeline`, `CgShader`), and
**CrystalGUI imports `com.crystalgraphics.gl.state.{CgGlScope, CgGlState}` directly** in
`CgUiPaintContext`. Since CrystalGraphics is an `includeBuild` composite, deleting them breaks
CrystalGUI's build in the same commit.

**Resolution: keep both types and both signatures; replace their implementations. Nothing is
deprecated.**

| Type | Was | Becomes |
|---|---|---|
| `CgGlSlot` | slot enum for capture granularity | **unchanged** — also the manager's group key |
| `CgGlScope` | holds `SlotState[]`, restores GL values captured at save | the same `AutoCloseable` restore point, backed by a pooled shadow snapshot |
| `CgGlState.save(slots…)` | 16-slot `glGet` capture dispatcher | same signature; adopts-or-snapshots by nesting depth, no `glGet` in the steady path |
| `CgShader.bindScoped()` | — | **unchanged** |

This turns a coordinated two-repo breaking change into a **single-repo, behaviour-preserving
refactor**. CrystalGUI compiles untouched, `bindScoped()` keeps its documented idiom, and every
existing `CgGlState.save(...)` call site keeps working while silently stopping issuing `glGet`.

> **Revised 2026-07-30:** an earlier version of this resolution kept the API only as a `@Deprecated`
> compatibility shim, to be deleted once call sites migrated to a new `push()` verb. That was the wrong
> instinct — there is no defect in the API to migrate away *from*. `save`/`scope`/`slot` is the right
> surface, "save" correctly describes *intent* rather than mechanism, and keeping it as the primary API
> is what enables depth-driven adoption (see [the four operations](#the-four-operations)). The old plan
> had a step to delete these; that step is gone.

### 3. ✅ `CgRenderState.clear()` — **kept and rerouted** (my earlier "dead code" call was wrong)

> **Corrected 2026-07-30 during implementation.** This section previously claimed `clear()` had zero
> callers and should be deleted. **That was wrong, and the error was mine**: the grep behind it searched
> only `api/material/`, `render/` and `gl/material/`, and missed `gl/render/` entirely. Deleting it broke
> the build immediately, which is the only reason it was caught.

`clear()` has **two real callers**, both in the batch render layer system:

| Caller | Pattern |
|---|---|
| `CgRenderLayer:55` | `state.apply(); renderer.flush(); state.clear();` |
| `CgDynamicTextureRenderLayer:118` | same |

So it is load-bearing: it stops one render layer leaking state into the next. The three
"Do NOT call `rs.clear()`" javadocs (`CgMaterial:625`, `CgForwardRenderer:34`,
`CgTransparentRenderer:29`) mean *the material and pipeline paths* must not call it, because those
manage state themselves — not that nobody may.

**Resolution: keep it, route it through the manager.** Writing GL directly there would leave the shadow
describing the pre-clear state, after which the manager would confidently elide calls that were needed —
the exact desync this design exists to prevent. Reset values map onto records cleanly, with two
deliberate, documented differences (`CgBlendState.DISABLED` skips a pointless blend-equation reset;
`CgCullState.NONE` additionally restores `glFrontFace(GL_CCW)`, which is the GL default).

Required one small addition: **`CgDepthState.GL_DEFAULT`** (`test=false, write=true, func=GL_LESS`).
`NONE` is `(false, false, GL_ALWAYS)` — *not* the GL default, so reusing it would have silently left
depth writes disabled after every reset.

**Lesson worth keeping: a "zero callers" claim is only as good as the search scope.** Scope the grep to
the whole repo, or state the scope you searched.

### 4. ✅ Null-slot semantics — found late, resolved by reordering

Found while closing the above, and it is the most important thing in this section. `CgRenderState`'s
javadoc makes null slots load-bearing:

> a material with no `Depth` block produces a state with `depth == null` — meaning **the depth settings
> of the prior pass are left unchanged**. This prevents the transparent pass from silently overwriting
> ZWrite / DepthFunc when the material doesn't declare a Depth block.

Trace what the per-bind save actually buys. Material A binds (saves baseline S), `apply()` sets only
its non-null slots, draws, unbinds (restores S). Material B then binds and *also* sees S — because A
restored it. **So today every material effectively starts from the pass baseline.**

Delete the per-bind save naively and that breaks: A sets blend, never restores, then B — with
`blend == null` — **draws with A's blend instead of the pass baseline.** No exception, just wrong
blending in a material that declared no `Blend` block. Exactly the class of bug the javadoc is guarding
against.

**Requirement this imposes:** a null slot must mean *restore that group to the burst baseline*, not
*skip it*. Under the manager that is cheap — one diff against the baseline the burst already holds —
and it is strictly **more** correct than today's behaviour, which only lands on the baseline because
every material happens to restore.

**Consequence: the headline win depends on the baseline machinery, so steps 3 and 4 had to swap.**
The per-bind save cannot be deleted until bursts exist. The step order below reflects this.

---

## Open items — none of these block step 1

Ordered by the step they gate. All four blockers above are closed; these are the remainder, and every
one of them can be resolved while earlier steps are already in flight.

### 1. Iris / Oculus interaction on mc1201 — unverified risk · gates step 9

Iris mutates Blaze3D state and mixes `setUnknownState()` into `GlStateManager.BooleanState` for its
own bypasses. So a field our reflection reads may be one Iris has *marked unknown* — in which case
the cached value is meaningless and we would adopt a lie. `CgRenderPipeline.isIrisActive()` already
exists, so the escape hatch is available (fall back to `glGet` for affected groups when Iris is
present). **Not verified — no Iris sources checked out.** Do this before shipping the mc1201 provider.

### 2. GL context lifecycle · gates step 1 (one-liner)

`destroyContext()` must drop the provider and mark the whole shadow unknown; a shadow describing a
dead context must never be trusted by a successor. Cheap, but currently unspecified. Note that root
`AGENTS.md` records `destroyContext()` firing only at game shutdown, so there is no destroy-then-init
cycle to defend — which makes this a one-liner rather than a design problem.

### 3. Kill switch for rollout · wanted before step 5 ships

The old system had `-Dcrystalgraphics.boundary.forceGlGet`. A replacement is worth having while this
is new: a flag that forces adopt-per-bind (i.e. reproduces today's behaviour) turns "the state manager
broke something" from a bisect into a single restart. Cheap insurance for a change that alters every
draw call in the engine.

### 4. The `CgGL` guard's actual method list · gates turning dedup on

Named as a hazard, deferred as a decision. Needs the concrete list of `CgGL` state setters that become
manager-only, plus the enforcement mechanism — most likely a `doLast` check mirroring the existing
import guard. Until this exists, nothing stops a future call site bypassing the shadow.

### 5. A dedicated benchmark scene · wanted before step 5

Verification currently leans on `text-3d`, which found the stall by accident. A purpose-built
state-thrash scene — N materials with alternating render states — would measure
`glstate.callsIssued / callsSkipped` directly and isolate the manager from text-shaping noise. Worth
having *before* step 5, so the headline win is measured on something built to measure it.

---

## What this deliberately does not fix

**Foreign raw-GL mutation inside a burst.** Undetectable without the omniscient interception that
Angelica proves does not exist. It is bounded structurally instead — bursts are short, Tier 2 covers
every known re-entry point, and the residue is accepted. This is the same bargain Sodium and Iris
make, and it is strictly better than the status quo, which pays 25 sync points per bind for a
guarantee it *also* does not actually provide.

**Back-face stencil.** `StencilState` captures front-face only today (documented as a pre-existing
limitation). Carrying that forward is fine; widening it is out of scope here.

---

## Verification

- **Unit** — shadow diffing: repeated identical `apply` issues zero calls; each changed field issues
  exactly one; `invalidate` forces a full re-issue; `release` writes back exactly the diff.
- **Harness** — `text-3d`, the scene that produced the original measurement. Compare
  `doBind.stateSave` (should vanish), whole-frame time, and **frames over 20 ms with the maximum
  reported**, not percentiles.
  > The original 347 ms stall was invisible in p50/p90 (7.5 / 8.6 ms) and was caught by a human
  > watching the screen. Count frames over a threshold and report the max, or this class of
  > regression will hide again.
- **Visual** — `cgui-gallery`, `forward-renderer`, `material-dual-path`. A state manager that
  wrongly skips a call produces *wrong rendering*, not a crash, so visual confirmation is
  load-bearing here in a way it usually is not.
- **Cross-platform** — each loader's `runClient` at least boots and renders, since Tier 0 fidelity
  differs per platform and a bad `adopt` will look like corrupted state rather than an exception.

---

## Prior art in this repo — reconciled

Two earlier plans cover adjacent ground. Both were found during this planning pass; leaving them
unreconciled would be a trap.

**`.sisyphus/plans/gl-state-overhaul.md` — completed, historical.** This is the plan that *produced
the code being replaced* (`CgGlSlot` → `SlotState` → `CgGlStates` → `CgGlScope` → `CgGlState`). Not a
conflict; it is the predecessor. Useful as the record of why the current shape exists.

**`.sisyphus/plans/render-system-overhaul.md` — superseded, never executed.** 772 lines, and
`CgRenderSystem` / `CgGlStateManager` / `CrossApiTransition` do not exist, so none of it landed. It
now carries a SUPERSEDED banner pointing here.

It **agrees** on deleting `GLStateMirror` and on building a `CgGlStateManager` (same name — kept
deliberately). It **diverges** on three points that matter:

| Its decision | Why superseded |
|---|---|
| "No caching for non-observed domains" | Inherited from `render-system-designs.md` §5.4. Keeps `glGet` for blend/depth/cull — i.e. **executing that plan would preserve the 347 ms stall.** It predates the profiling and never mentions `doBind`, `glGet`, sync points or boundaries. |
| "Build `CgRenderSystem`" | Already exists as `CgGL`. That plan predates the platform abstraction. |
| ~~"Remove `CallFamily`"~~ | **This entry was wrong.** Reversed 2026-07-31: the nine "dependents" were producers with no consumer. Removed. |

**Harvested from it:** its "Codebase Ground Truth" section is verified and accurate, and its
`CoverageMatrix` FULL_MODE / GAP_ONLY_MODE listings independently confirm the transformer observes
only four domains (FBO, program, activeTexture, bindTexture) and explicitly **not**
blend/depth/cull/scissor/stencil/VAO/VBO — which is the quantitative basis for scrapping it.

---

## Appendix — critical review of `research/render-system-designs.md`

That document researches Blaze3D, IrisRenderSystem, Angelica GLSM and Forge 1.7.10 GlStateManager,
then recommends an architecture for CrystalGraphics. **Its source research (Section 1, §5.1–5.3) is
solid and worth keeping. Its recommendations (Section 2) are largely superseded** — it predates the
platform abstraction and `CgGL` entirely, and its central conclusion is the direct cause of the
stall this plan removes. Treated source-by-source so nothing good is thrown out with it.

### Still correct — adopt

| § | Finding | Status here |
|---|---|---|
| 5.1 | Interface-based multi-API dispatch (`DSAAccess` / `RenderBackend` / one interface, N backends, chosen at startup) | **Vindicated** — already `CgGLBackend`. |
| 5.2 | All three reference systems assert render thread before every GL call | **Genuinely missing in CrystalGraphics.** `CgGL` has no `assertOnRenderThread()`. Worth adding — a state manager is *more* sensitive to off-thread calls than a stateless facade, because a bad call corrupts the shadow rather than just failing. |
| 5.3 / 2.5 | Iris's `setUnknownState()` — mark a domain unknown after a bypass, forcing the next call to issue for real | **This is Tier 2 exactly.** Strongest borrowing in the document; `BooleanStateExtended` is the precedent to copy. |
| 2.8 | Sampler-object binding cache is safe because CrystalGraphics exclusively owns sampler objects | Principle correct — see below, it is *more* general than the doc realised. |

### Superseded — do not follow

| § | Recommendation | Why it fails now |
|---|---|---|
| 2.1, 2.3, 2.7, 2.9, 2.10 #2 | "`GLStateMirror` **is** your GlStateManager for the observed domains. Keep it as-is." | Covers 3 of 16 domains, on 1 of 4 platforms, with **zero invalidation path** (`markUnknown()` unused), and Angelica defeats the observation anyway. |
| 2.2, 2.10 #1 | "Create `CgRenderSystem` as a thin public facade." | **Already exists — it is `CgGL`.** Doc predates it. Creating a second facade would duplicate 482 methods. |
| 2.4, 5.4 | "Full redirect is the only sound basis for universal caching… **there is no third option that works correctly.**" | The load-bearing error. See below. |
| 2.7 | "`CgGlState`… safe because it reads actual GL state, not a possibly-stale cache." | True and irrelevant — the sentence that cost 347 ms. It buys correctness with a driver sync per bind, to protect state `CgRenderState.apply()` then overwrites unconditionally. |
| 2.9 | Texture bindings → "Raw GL; if needed, `glGetInteger`" | Became `TextureState.capture()`, which loops `glActiveTexture` + `glGetInteger` over **up to 32 units** — the worst single instance of the pattern in the codebase. |
| 2.10 #5 | "**Do NOT** introduce a `CgGlStateManager`… the name implies Blaze3D's caching behaviour, which is unsafe here." | Inverted. The name is now *correct* precisely because the semantics match. The doc's own naming worry is answered by the boundary contract, which Blaze3D lacks. |

### The load-bearing error, precisely

§5.4 claims:

> Every system that can't observe ALL callers either (1) does not cache those domains, **or**
> (2) redirects ALL callers. There is no third option that works correctly. A cache that only
> observes some callers will diverge.

Every sentence is true **for a read-cache** — one that consults its shadow to skip work it did not
itself perform. The framing is what fails: it treats *observability* as a static property of a **GL
domain**, when it is actually a property of a **scope**.

Blend is unobservable across a frame. It is perfectly *owned* inside a 200 µs burst that begins with
an authoritative pour and ends with a diff-restore. The third option §5.4 says cannot exist is:
**never read; assert, and re-establish truth at declared boundaries.**

The document concedes this principle without noticing, in its own §2.9: VAO, VBO and uniform-buffer
bindings are marked *"N/A (exclusive) → safe to cache."* Exclusive ownership licenses caching. This
plan simply observes that ownership is **temporal**, not per-domain.

And its §2.6 Q1 supplies the empirical refutation: it notes Blaze3D's `GlStateManager` assumes
single-owner control and diverges on any raw GL call from any mod — yet Mojang ships exactly that to
hundreds of millions of modded installs. Divergence in a *write-dedup* cache is a redundantly skipped
write, recovered at the next re-assert. It is not the catastrophe §5.4 implies. §2.4 was right that
expanding the transformer would collide with Angelica; it was wrong that `glGet` was the alternative.

### Verdict

Keep Section 1 as reference. Keep §5.1–5.3 and §2.8 as live guidance. **Treat all of Section 2's
architecture recommendations as superseded by this document**, and add the one thing it identified
that is still genuinely missing: **render-thread assertion**.

---

## Expected effect

`doBind` drops from ~25 `glGet` sync points to **0–6 state writes** — no reads at all. That alone is
the whole `doBind` cost.

Per-frame `glGet` count, which today is `~25 × material binds` and unbounded in the number of binds:

| Platform | After |
|---|---|
| mc1710 + Angelica | **0** |
| mc1201 ×3 | ~3–13 per burst × 3–4 bursts |
| mc1710 vanilla · harness | ~40 × 3–4 bursts |

The key change is not the constant — it is that the count stops scaling with **draw-call volume** and
starts scaling with **pass count**, which is fixed at 3–4 per frame. A scene with 200 material binds
pays the same as a scene with 2.

Against the recorded worst frame — 346.8 ms of `stateSave` inside a 349.9 ms frame — that is the
entire stall.
