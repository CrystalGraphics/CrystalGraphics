# api/state — Render State Slot System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Immutable render state descriptors used by the batch rendering layer system and
the CrystalShader material pipeline. Each class represents one GL state slot
(blend, depth, cull, stencil, or texture). These are pure policy objects — they
know what GL state to set, but they don't own shaders, textures, or GPU resources.

Also contains `CgGlSlot`, the enum used by the GL state save/restore framework.

## `CgGlSlot` — GL State Save/Restore Enum

16-constant enum identifying GL state domains that `CgGlState.save()` can independently
capture and restore. Pass specific slots to save only what you intend to modify.

| Group | Constants |
|---|---|
| Binding slots | `FBO`, `PROGRAM`, `TEXTURES`, `VERTEX_INPUT` |
| Render state | `BLEND`, `DEPTH`, `CULL`, `STENCIL` |
| Output / framebuffer | `COLOR_MASK`, `VIEWPORT`, `SCISSOR`, `POLYGON_OFFSET` |
| Raster / fixed-function | `ALPHA_TEST`, `LINE_WIDTH`, `POLYGON_MODE`, `POINT_SIZE` |

No GL calls, no Minecraft imports. Safe to reference from any layer.

See `gl/state/AGENTS.md` for the full save/restore framework documentation.

## Type Map

| Type             | Role |
|------------------|------|
| `CgRenderState`  | Immutable composite: blend + depth + cull + stencil slots. `apply()` applies all slots; `clear()` restores GL defaults. One instance per render layer or material pass. `DEFAULT` constant = blend disabled, depth TEST_WRITE, cull BACK, stencil DISABLED. |
| `CgDepthState`   | Depth test + depth write + compare function. Pre-defined: `NONE`, `TEST_ONLY`, `TEST_WRITE`, `TEST_WRITE_EQUAL`, `TEST_WRITE_ALWAYS`. |
| `CgBlendState`   | Blend policy + blend equations. Pre-defined: `DISABLED`, `ALPHA`, `PREMULTIPLIED_ALPHA`, `ADDITIVE`, `MULTIPLY`. Static `clearToDefault()` for use by `CgRenderState.clear()`. |
| `CgCullState`    | Face culling policy. Pre-defined: `NONE`, `BACK`, `FRONT`. |
| `CgStencilState` | Stencil test policy. Pre-defined: `DISABLED`. `apply()` dispatches `glStencilFunc/Mask/Op`; `clear()` disables stencil and resets write mask. |
| `CgTextureState` | Texture-bind policy with three modes: `none()`, `fixed(target, id, unit, sampler)` / `fixed(CgTexture, unit, sampler)`, `dynamic(target, unit, sampler)`. Not part of `CgRenderState` — caller's responsibility. |

## `CgRenderState` — Shader and Texture Coupling Removed (T8)

`CgRenderState` no longer holds a `CgShader` reference, texture binding, or projection uniform.
It is a **pure GL state descriptor** — blend, depth, cull, stencil only.

- `builder()` takes no shader argument.
- `apply()` takes no parameters — just applies the four state slots.
- `clear()` restores all four slots to GL defaults.
- Shader binding, texture binding, and projection uniforms are the **caller's responsibility**.

## Ownership Model

- Slots are **shareable**: the same `CgDepthState.NONE` instance can be used by many render states.
- Slot objects are immutable and allocation-free after construction.

## Apply/Clear Contract

Every `CgRenderState.apply()`:
1. Applies blend (enable/disable + func + equations)
2. Applies depth (test enable/disable + write mask + compare func)
3. Applies cull (enable/disable + face)
4. Applies stencil (enable/disable + func + mask + ops)

Every `CgRenderState.clear()`:
1. `CgBlendState.clearToDefault()` — disable blend, reset equations to GL_FUNC_ADD
2. `depth.clear()` — disable depth test, write=true, func=GL_LESS
3. `cull.clear()` — disable cull face
4. `CgStencilState.DISABLED.apply()` — disable stencil test

This bracketed pattern prevents GL state leaks between render layers and material passes.

## Design Rules

- **Never add GL resource ownership** to these types. They are descriptors.
- **Never add mutable state** — all fields are final.
- **No shader or texture references** in `CgRenderState` — removed in T8.
- **Prefer pre-defined constants** for depth/cull/stencil. Custom instances allowed but rare.
