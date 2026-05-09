# api/state — Render State Slot System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Immutable render state descriptors used by the batch rendering layer system.
Each class represents one GL state slot (depth, cull, texture, or the
composite render state). These are pure policy objects — they know what GL
state to set, but they don't own shaders, textures, or GPU resources.

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

| Type             | Role                                                                                                                                                                       |
|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CgRenderState`  | Immutable composite: shader ref + blend + depth + cull + texture slots. `apply(projection)` sets all GL state; `clear()` restores defaults. One instance per render layer. |
| `CgDepthState`   | Depth test + depth write policy. Pre-defined: `NONE`, `TEST_ONLY`, `TEST_WRITE`.                                                                                           |
| `CgBlendState`   | Blend policy + blend func policy. Pre-defined: `DISABLED`, `ALPHA`, `PREMULTIPLIED_ALPHA`, `ADDITIVE`.                                                                     |
| `CgCullState`    | Face culling policy. Pre-defined: `NONE`, `BACK`, `FRONT`.                                                                                                                 |
| `CgTextureState` | Texture-bind policy with three modes: `none()` (no texture), `fixed(target, id, unit, sampler)` / `fixed(CgTexture, unit, sampler)` (static atlas), `dynamic(target, unit, sampler)` (texture ID per-flush). |

## Relationship to `CgBlendState`

`CgBlendState` lives in `gl/pass/` (it predates the batch layer system and is
shared by the render pass system). `CgRenderState` references it as a slot but
does not duplicate or re-export it.

## Relationship to `CgTexture`

`CgTextureState.fixed` accepts either a raw `(target, textureId)` pair or a
`CgTexture` instance from `api/texture/`. The state object only references the
texture by id; lifecycle remains the caller's responsibility. The previous
`CgTextureBinding` value type has been removed — its (target, id) role is now
inlined into the `CgTextureState` record itself.

## Ownership Model

- Slots are **shareable**: the same `CgDepthState.NONE` instance can be used
  by many `CgRenderState` builders.
- `CgRenderState` does **not own** the shader or texture — it holds references.
  Lifecycle management of shaders/textures is the caller's responsibility.
- Slot objects are immutable and allocation-free after construction.

## Apply/Clear Contract

Every `CgRenderState.apply(projection)`:
1. Binds shader (with projection uniform)
2. Binds texture (if any)
3. Applies blend, depth, cull

Every `CgRenderState.clear()`:
1. Disables cull
2. Disables depth
3. Disables blend (`CgBlendState.DISABLED.apply()`)
4. Unbinds texture
5. Unbinds shader

This bracketed pattern prevents GL state leaks between render layers.

## Design Rules

- **Never add GL resource ownership** to these types. They are descriptors.
- **Never add mutable state** — all fields are final.
- **Prefer pre-defined constants** for depth/cull. Custom instances are allowed
  but should be rare.
- **CgTextureState.dynamic** requires the overrideTextureId to be supplied at
  `apply()` time by the owning layer (e.g. `CgDynamicTextureRenderLayer`).
