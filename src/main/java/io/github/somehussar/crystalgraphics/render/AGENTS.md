# render — CgRenderPipeline Orchestrator

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../AGENTS.md)

## What This Package Is

Contains `CgRenderPipeline` — the singleton orchestrator for the CrystalGraphics forward
render pipeline. Called once per MC frame from the `CgRenderHook` Mixin.

## Class Map

| Type | Role |
|------|------|
| `CgRenderPipeline` | Singleton. `getInstance()` lazily creates on first call. `getFrameConfig()` exposes the `CgFrameConfig` for per-frame population. `acquireCommand()` + `submit(cmd)` are game-code entry points. `execute(partialTicks)` runs depth prepass → forward → transparent. `destroy()` is called by `CgGraphicsLifecycle.destroyContext()` step 7d. |

## Execute Sequence (Phase 1 MVP)

```
execute(partialTicks):
  0. Empty-queue fast path: if no commands → return immediately (no GL, no scope)
  1. Anaglyph guard: compute invocationId; detect replay (second eye in anaglyph mode)
  2. try { try (CgGlScope = CgGlState.saveAll()) {
       a. (non-replay): commandQueue.sort()
       b. updateFrameUniforms(partialTicks)
       c. CgMaterialPipeline.getInstance().beginFrame()
       d. boolean prepassRan = depthPrepass.execute(...)
       e. int depthTestMode = prepassRan ? GL_EQUAL : GL_LEQUAL
       f. forwardRenderer.execute(..., depthTestMode, ...)
       g. transparentRenderer.execute(...)
     } // scope.close() restores ALL GL state
  } finally { if (!replay) commandQueue.releaseAll() }
```

## Lifecycle

`CgGraphicsLifecycle.destroyContext()` calls `CgRenderPipeline.destroy()` at step 7d
(after `CgMaterialPipeline.destroy()`, before `CgFrameBufferRegistry.deleteAll()`).
