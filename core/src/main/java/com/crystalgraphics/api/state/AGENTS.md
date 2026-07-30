# api/state — Render State Slot System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Immutable render state descriptors used by the batch rendering layer system and
the CrystalShader material pipeline. Each class represents one GL state slot
(blend, depth, cull, stencil, or texture). These are pure policy objects — they
know what GL state to set, but they don't own shaders, textures, or GPU resources.

> **`CgGlSlot` is no longer here.** It moved to **`com.crystalgraphics.platform.gl.state`** in the
> 2026-07-31 state rewrite, along with `CgGlState`, `CgGlScope` and the provider SPI — deduplication happens
> where the GL call is made, which is `CgGL` in `platform`. (`CgGlStateManager` itself sits one level up in
> `platform.gl`, beside the `CgGL` that calls into it on every setter.) This guide described it as living here; it does not.

## How these records reach GL

Each record's `apply()` calls `CgGL` directly. `CgGL` deduplicates against a CPU-side shadow and issues only
what actually changes, so calling `apply()` with the value already in effect costs nothing and needs no
caller-side guard.

They no longer implement a common `CgStateGroup` interface — that abstraction existed to let the old
core-side manager store and re-emit them, and went away with it. They are plain immutable records again.

Framework documentation: `platform/src/main/java/com/crystalgraphics/platform/gl/state/`, summarised in
`gl/state/AGENTS.md`.

## Type Map

| Type             | Role |
|------------------|------|
| `CgRenderState`  | Immutable composite over **six** slots: blend, depth, cull, stencil, alpha and a list of `CgColorMask`. A null slot means *undeclared* — `apply()` leaves it alone rather than forcing a default. One instance per render layer or material pass. `DEFAULT` = blend disabled, depth TEST_WRITE, cull BACK, stencil DISABLED. |
| `CgDepthState`   | Depth test + depth write + compare function. Pre-defined: `NONE`, `TEST_ONLY`, `TEST_WRITE`, `TEST_WRITE_EQUAL`, `TEST_WRITE_ALWAYS`, and `GL_DEFAULT` (the GL initial value — test off, write on, `GL_LESS` — used by `clear()`). |
| `CgBlendState`   | Blend policy + blend equations. Pre-defined: `DISABLED`, `ALPHA`, `PREMULTIPLIED_ALPHA`, `ADDITIVE`, `MULTIPLY`. `clearToDefault()` exists but **is no longer called by `CgRenderState.clear()`**, which applies `DISABLED` instead; same for `CgColorMask.clearToDefault()`. Both are unreferenced public API today. |
| `CgCullState`    | Face culling: enabled + face + **winding** (`frontFace`). Three components, not two — the 2-arg constructor is a convenience defaulting to `GL_CCW`. Pre-defined: `NONE`, `BACK`, `FRONT`. `NONE` carries `GL_BACK` rather than `0`, so disabling culling never writes a bogus face mode. |
| `CgStencilState` | Stencil test policy. Pre-defined: `DISABLED`. `apply()` dispatches `glStencilFunc/Mask/Op`; `clear()` disables stencil and resets write mask. |
| `CgTextureState` | Texture-bind policy with three modes: `none()`, `fixed(target, id, unit, sampler)` / `fixed(CgTexture, unit, sampler)`, `dynamic(target, unit, sampler)`. Not part of `CgRenderState` — caller's responsibility. |

## `CgRenderState` holds no shader or texture

It is a **pure GL state descriptor**: blend, depth, cull, stencil, alpha and colour masks. Shader binding,
texture binding and projection uniforms are the **caller's** responsibility, and `builder()` takes no shader
argument.

> An earlier revision of this guide said "the four state slots" throughout. There are six — alpha and the
> `CgColorMask` list were added later, and a `clear()` that only restored four would leak the other two.

## Ownership Model

- Slots are **shareable**: the same `CgDepthState.NONE` instance can be used by many render states.
- Slot objects are immutable and allocation-free after construction.

## Apply/Clear Contract

`apply()` applies only the **non-null** slots. A null slot is *undeclared*, meaning the prior pass's value
stands — this is what stops a transparent pass that declares no `Depth` block from silently overwriting
ZWrite.

`clear()` restores a declared slot by **applying that slot's GL-default constant** — `CgDepthState.GL_DEFAULT`,
`CgCullState.NONE`, `CgAlphaState.DISABLED`, `CgColorMask.ALL` — rather than calling a bespoke `clear()` on it.
Restoring is just applying a known value, so there is no second code path that can drift from `apply()`.

Blend and stencil are reset **unconditionally**, even when undeclared: both are global GL state that must not
leak into the next pass regardless of what the material said.

> All of this routes through `CgGL`, which deduplicates against its shadow. Clearing a slot that is already at
> its default therefore issues **no GL call at all**, so the bracketed apply/clear pattern is safe to use
> liberally — it costs only what actually changed.

## Design Rules

- **Never add GL resource ownership** to these types. They are descriptors.
- **Never add mutable state** — all fields are final.
- **No shader or texture references** in `CgRenderState` — removed in T8.
- **Prefer pre-defined constants** for depth/cull/stencil. Custom instances allowed but rare.
