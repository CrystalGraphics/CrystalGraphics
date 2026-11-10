# api/state — Render State Slot System

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../AGENTS.md)

## What This Package Is

Immutable render state descriptors used by the material pipeline and the canvas. Each class is one slot of a
pipeline's fixed-function state (blend, depth, cull, stencil, colour masks). They are pure policy: they own no shader,
texture or GPU resource, and set nothing themselves.

## How these records reach the device

A pipeline's description is built from them: `render.draw.CgDeviceStates` turns a `CgRenderState` into a device's
`CgGraphicsPipelineDesc` pieces (Unreal's `FGraphicsPipelineStateInitializer` from a material's state), and
`CgPipeline.device` assembles them. A draw under a different state is a different pipeline, cached by its
description.

```java
CgRenderState state = CgDeviceStates.over(passState, pipeline.state());   // the pass's, the pipeline's over it
CgGraphicsPipelineDesc.Raster raster = CgDeviceStates.raster(state.getCull());
```

## Type Map

| Type             | Role |
|------------------|------|
| `CgRenderState`  | Immutable composite over five slots: blend (and a second target's), depth, cull, stencil, and a list of `CgColorMask`. A null slot means *undeclared*: the pass's value stands (`CgDeviceStates.over`). One instance per render layer or material pass. `DEFAULT` = blend disabled, depth TEST_WRITE, cull BACK, stencil DISABLED. |
| `CgDepthState`   | Depth test + depth write + compare function. Pre-defined: `NONE`, `TEST_ONLY`, `TEST_WRITE`, `TEST_WRITE_EQUAL`, `TEST_WRITE_ALWAYS`, and `GL_DEFAULT` (test off, write on, `GL_LESS`). |
| `CgBlendState`   | Blend policy + blend equations. Pre-defined: `DISABLED`, `ALPHA`, `PREMULTIPLIED_ALPHA`, `ADDITIVE`, `MULTIPLY`. |
| `CgCullState`    | Face culling: enabled + face + **winding** (`frontFace`). The 2-arg constructor defaults to `GL_CCW`. Pre-defined: `NONE`, `BACK`, `FRONT`. `NONE` carries `GL_BACK` rather than `0`. |
| `CgStencilState` | Stencil test policy. Pre-defined: `DISABLED`. Its reference is the pass's to set (`CgRenderPass.setStencilReference`), not the pipeline's. |

## `CgRenderState` holds no shader or texture

Shader binding, texture binding and projection uniforms are the **caller's**, and `builder()` takes no shader. There
is no alpha test: a shader discards (a `.shader`'s `AlphaTest` is refused at parse).

A null slot is *undeclared*, meaning the pass's value stands: this is what stops a transparent pass that declares no
`Depth` block from silently overwriting ZWrite.

## Design Rules

- **Never add GPU resource ownership** to these types. They are descriptors.
- **Never add mutable state**: all fields are final.
- **Prefer pre-defined constants** for depth/cull/stencil. Custom instances allowed but rare.
- Slots are **shareable**: the same `CgDepthState.NONE` instance can be used by many render states.

---

Also loaded with this folder:

@../../../../../../../../docs/ENGINE_API.md
