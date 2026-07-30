# `gl/state` — what is left of it

**Rewritten 2026-07-31 (V2).** This package used to hold the whole GL state framework. It no longer does:
`CgGlState`, `CgGlScope`, `CgGlSlot`, the shadow and the providers now live in
**`com.crystalgraphics.platform.gl.state`**, and `CgGlStateManager` in **`com.crystalgraphics.platform.gl`**
beside the `CgGL` that calls into it.

**This package is now empty of code** — only this guide remains.

`CallFamily` is **deleted**. It was defended twice as "live and load-bearing, nine files outside this
package use it" — that was a count of files *referencing* it, not of code *using* it. Every one was a
`protected CallFamily callFamily() { return CONSTANT; }` override of an abstract method with **zero
callers**; its only consumer, `CrossApiTransition`, had been deleted long before, leaving javadoc `{@link}`s
pointing at a class that no longer existed. `CgFrameBuffer.wrap()` took one as a parameter, validated it,
stored it — and `wrap()` had no callers either.

Where the concept genuinely belongs is `CgCapabilities.FramebufferPath` (`CORE_GL30`/`ARB_FBO`/`EXT_FBO`/
`NONE`) in `platform`, which is where capability detection lives and where the waterfall is actually
selected. `CallFamily` sat in a *state* package because the mirror once tagged bindings with it.

> **The lesson, since it has now cost two wrong claims in one session:** `grep -l` counts files that mention
> a symbol. It does not distinguish a caller from an override, a javadoc link, or an import. Before calling
> something load-bearing, search for *invocations*.

`GLStateMirror` is **gone**, along with the entire ASM coremod that fed it (`CrystalGraphicsCoremod`,
`CrystalGraphicsTransformer`, `CrystalGLRedirects`, `CoverageMatrix`) and their tests. Process-wide bytecode
interception could never be authoritative — ours was modelled on Angelica's, targeted the same call sites,
and was observed being redirected *into* Angelica's. `AngelicaStateProvider` reads Angelica's own mirror
instead, which is strictly more complete and costs nothing to maintain.

Design record, including the V2 rationale and every correction made along the way:
`docs_research/CGGLSTATEMANAGER_PLAN.md`

---

## Why it moved

Deduplication has to happen **where the GL call is made**, and every GL call in this project goes through
`CgGL`, which lives in `platform`. With the manager in `core`, keeping the shadow truthful required callers
to report their writes — an extra notification next to every state call. That is a design that depends on
nobody ever forgetting, and it was already being forgotten: 46 notification sites across 8 files, and a base
class was still missed.

So the write and the tracking became the same statement. Every `CgGL` setter now reads:

```java
public static void glDepthMask(boolean flag) {
    if (state().depthMaskChanged(flag)) backend.glDepthMask(flag);
}
```

There is no way to write GL state without the shadow seeing it, because there is no other path. The
`cgStateWriteGuard` build task that used to police this was deleted along with the problem — raw `CgGL` is
now exactly as safe as the typed records.

## Where to read about the framework now

| Package | Holds |
|---|---|
| `platform.gl` | `CgGlStateManager` — the engine, beside `CgGL`, which consults it on every state setter |
| `platform.gl.state` | `CgGlState` (facade), `CgGlScope`, `CgGlSlot`, `CgGlStateShadow`, `CgGlStateProvider`, `CgGlGetProvider` |

The split follows the call direction: `CgGL` depends on the manager, and the manager depends on the value
and SPI types — so only the manager needs to sit next to `CgGL`.

The short version of what changed beyond the move:

- **Fourteen of sixteen domains dedupe.** V1's allow-list (six) existed because binding domains could not
  *report* reliably; routing through `CgGL` removed that constraint. Two are still exempt, for a different
  reason — see below.
- **Per-field, not per-record.** The shadow is one flat mutable struct; setting only the depth *mask* twice
  is recognised as redundant without the func being involved.
- **Restore is not a second write path.** It re-issues through `CgGL`, so an undisturbed domain emits
  nothing and there is no restore implementation that can drift from the apply one.
- **`CgStateGroup` is gone.** The `api/state` records are plain records again; their `apply()` calls `CgGL`.

## The invariants that actually bite

These are the ones that have produced real bugs. All of them fail as a **missing GL call** — wrong
rendering, no exception, nowhere near the cause.

1. **Only genuinely global state may be deduped.** Anything an object binding implicitly swaps must be
   invalidated when that object changes. `GL_ELEMENT_ARRAY_BUFFER` is **per-VAO state** —
   `glBindVertexArray` swaps it with no `glBindBuffer` to observe. Tracking it as a global meant a shared
   IBO looked already-bound after a VAO switch, so the bind was elided and the VAO was left with no index
   buffer; it surfaced as *"Cannot use offsets when Element Array Buffer Object is disabled"* at a draw call
   several binds later. `GL_ARRAY_BUFFER` is genuinely global and needs no such treatment.

2. **Code that resets GL state wholesale must call `CgGlState.invalidateAllIfPresent()`.** Raw GL outside
   `CgGL` is invisible by construction. The harness's `GlStateResetHelper` is the canonical case; omitting
   it left blending disabled and rendered every bitmap glyph as an opaque quad.

3. **Trust never survives leaving our control.** An *outermost* `save()` re-reads unconditionally; only a
   *nested* one may trust the shadow. Between two outermost scopes, Minecraft or another mod ran.

4. **A null `CgRenderState` slot means "leave alone", not "revert to baseline".** Callers legitimately
   configure ambient state *inside* a scope.

5. **Material bind/unbind is not LIFO**, so `CgMaterial` cannot hold a scope. Material isolation comes from
   the pass renderers re-asserting ambient state per batch.

## `PROGRAM` and `FBO` are tracked but never deduplicated

Deduplication is a bet that nothing wrote GL behind our back, and the stake differs sharply by domain.
These two are where it pays least and loses worst, so every write to them is issued:

| | Redundant call costs | Wrong value costs |
|---|---|---|
| `FBO` | a handful of binds per frame — nothing measurable | draws into the wrong target, often producing **no visible output at all** |
| `PROGRAM` | `glUseProgram` is cheap and never synchronises | wrong shader; MC, Iris and every shader mod rebind constantly |

`TEXTURES` and `VERTEX_INPUT` stay deduplicated: they are the high-frequency binds, where elimination is
worth the narrower risk.

Both exempt domains remain **tracked**, because the shadow is what a scope restores from — and always
issuing means restore cannot re-establish a stale remembered value either.

> **The trade is cheaper than it looks.** The measured win — 346.8 ms of `glGet` per frame down to 0.00 ms —
> came from removing driver synchronisation in *scope capture*, not from eliminating writes. Write
> deduplication is a second-order gain on top, so exempting a domain costs almost none of the actual saving.
> Verified: `text-3d` and `cgui-gallery` render identically with the exemption in place.

## Hosting Minecraft's renderers — `hostForeign`

This engine deliberately runs foreign rendering code *inside* its own passes: an item icon, an entity
preview, a block model in a CrystalGUI panel. That code writes GL through Blaze3D or raw calls, none of
which `CgGL` sees, so on the way out the shadow is not merely suspect — it is **known** to describe a world
that no longer exists. A plain `save()` trusts it across the block and deduplicates away precisely the calls
needed to undo what the foreign code did.

```java
try (CgGlScope s = CgGlState.hostForeign(CgGlSlot.BLEND, CgGlSlot.DEPTH, CgGlSlot.PROGRAM)) {
    minecraft.getItemRenderer().renderStatic(stack, ...);
}   // declared domains re-asserted for real; everything else marked unknown
```

Entry is free — no `glGet`, because our shadow is still truthful going in. The whole cost is one re-assert
of what you named, on the way out. Declaring nothing is valid: it invalidates without restoring.

> **This fixes our half only.** Minecraft keeps its own shadow (`GlStateManager` on 1.20.x, Angelica's on
> 1.7.10) and every write we make through `CgGL` is equally invisible to *it*. Before calling in, set the
> state MC cares about through **MC's** API so its mirror is truthful too. Two shadows, each blind to the
> other; this scope stands on only one side of that boundary. On 1.7.10 with Angelica the problem largely
> dissolves, because our provider reads Angelica's mirror, which observed both sides.

### Why re-establishing a domain suspends deduplication

Trust is tracked per **domain**, but a domain is written **field by field**. The first field re-issued marks
the domain trusted, after which every remaining field of that domain compares equal to the stale shadow and
is skipped. `DEPTH` emitted its enable and silently dropped both the write mask and the compare function —
two thirds of the domain left on whatever the foreign code set.

So `reissue` suspends deduplication for the duration of one stale domain (`forcing`). A domain nobody
disturbed still takes the normal path and usually emits nothing, so the fast case is unaffected.

## Diagnostics — reach for these before reasoning

Reasoning from symptoms produced a wrong answer three times in this subsystem's history; each of these gave
the right one in a single run.

| Flag | Effect |
|---|---|
| `-Dcrystalgraphics.state.verify=true` | Re-read the domain before eliminating a call and compare. Logs domain plus tracked-vs-actual on mismatch, then emits anyway. **Names the culprit.** Very slow — diagnosis only. |
| `-Dcrystalgraphics.state.noDedup=true` | Never eliminate. Separates "the shadow is lying" from "a semantic regression", and is the support answer for a user with a broken modpack. |

## Platform providers

| Platform | Source | `glGet` per adopt |
|---|---|---|
| 1.7.10 + Angelica | `AngelicaStateProvider` (mc1710), reads Angelica's mirror by reflection | near zero |
| 1.7.10 vanilla · harness | `CgGlGetProvider` | full sweep |
| 1.20.x | `Blaze3DStateProvider` exists but is **not compiled** — `mc1201` is absent from `settings.gradle.kts` | — |

> **Trap, found by reading Angelica's source rather than assuming:** its `DepthState.enabled` is the depth
> **write mask**, not the depth test — `glDepthMask` stores into it, and the test is a separate `depthTest`
> stack. This is the *opposite* of Blaze3D's convention; do not carry either workaround across. Angelica's
> `BlendState` also carries no blend equation, exactly like Blaze3D's.

Providers must be **total**. Extending `CgGlGetProvider` makes that structural: override what you can read
cheaply, inherit `glGet` for the rest, so a reflection miss costs performance and never correctness.

## What the tests can and cannot tell you

`CgGlStateManagerTest` (in `platform`) needs no GL context, because the `*Changed` methods are pure: they
compare against the shadow, record, and report whether the driver should be told.

That is also their limit. **They verify the manager decides correctly, not that its picture of the world is
true.** Every bug this subsystem has had passed the full suite — including the VAO one above, which was
found by running the harness. `verify` mode is what closes that gap.
