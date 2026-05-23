# api/render — CrystalGraphics Render Pipeline Public API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Public contracts for the CrystalGraphics forward render pipeline. Game code uses these
types to submit geometry for rendering each frame.

## Class Map

| Type | Role |
|------|------|
| `CgFrameData` | Per-frame mutable data bundle: `viewMatrix`, `projMatrix`, `timeSecs`, `viewportW/H`, `cameraPos`, `cameraForward`, `farPlane`, `worldTimeSampler`, `anaglyphModeEnabled`. Call `deriveFromViewMatrix()` to populate `cameraForward` and `cameraPos` from `viewMatrix`. `anaglyphModeEnabled` defaults to `false`; set to `true` in the mc/ layer when anaglyph stereo is active to enable the replay guard in `executeOpaquePass()`. Exposed by `CgRenderPipeline.getFrameData()`. |
| `CgRenderCommand` | One render submission: mesh + material + transform + AABB + sort metadata + `tag` + `preDrawHook`. Pre-allocated in `CgRenderCommandPool` — never instantiate directly. Set `worldAabb[0..5]` before `submit()`. `tag` (long) is a CPU-only identity slot for use inside `CgPreDrawHook`. `preDrawHook` is an optional hook fired before each instanced draw. |
| `CgRenderCommandPool` | Pre-allocated pool of `CgRenderCommand` slots. `acquire()` resets and returns a slot; `release(cmd)` is idempotent; `releaseAll()` is O(N) bulk-release used at frame-end. Grows with `Arrays.copyOf` on exhaustion. |
| `CgSortKey` | Static utility: encodes 64-bit sort keys. Opaque key layout (after T0+R2): `[63:60]` slot(4b) `[59:56]` priority(4b) `[55:40]` materialId(16b, stable per-instance ID) `[39:24]` depth(16b) `[23:8]` meshId(16b, identityHashCode for grouping) `[7:0]` reserved(8b). Transparent key: slot+priority+depth only (no materialId or meshId). `buildOpaqueKey()` now takes a `meshId` parameter. |
| `CgPreDrawHook` | `@FunctionalInterface` fired once per merged batch, after material bind, before the instanced draw. `apply(batch, offset, count)` — receives sorted slice in `gl_InstanceID` order. `only(fn)` — fail-fast helper for single-instance-only hooks. Fires in both depth prepass and forward pass. Commands merge only when they share the same hook reference (`==`). |
| `CgRenderCommandQueue` | Frame submission queue. `submit(cmd)` validates AABB, derives `normalMatrix`, computes `cameraDepth` + `passFlags` + `sortKey`. Uses `cmd.material.getMaterialId()` (stable) for materialId and `System.identityHashCode(mesh)` for meshId. `sort()` indirect-quicksorts and builds `sortedOpaque[]` + `sortedTransparent[]` filtered views. `releaseAll()` resets all counts and delegates to pool. |
| `CgRenderPipeline` | Singleton pipeline orchestrator. Owns the per-frame UBO and per-object SSBO/TBO. `acquireCommand()` / `submit(cmd)` are the game-code entry points each frame. `executeOpaquePass(partialTicks)` runs sort + UBO upload + depth prepass + opaque forward. Anaglyph guard is gated on `frameData.anaglyphModeEnabled` — defaults to `false` (no replay skip in harness mode). `executeTransparentPass()` runs transparent back-to-front pass. `endFrame()` releases pool. `execute(partialTicks)` is the single-call convenience wrapper. `getFrameData()` returns the mutable per-frame data holder. `init()` / `destroy()` manage the singleton lifecycle. |

## Key Usage

```java
CgRenderPipeline pipe = CgRenderPipeline.getInstance();
CgFrameData fd = pipe.getFrameData();
fd.viewMatrix.set(glViewBuf);
fd.projMatrix.set(glProjBuf);
fd.timeSecs = elapsedSecs;
fd.viewportW = width; fd.viewportH = height;
fd.deriveFromViewMatrix();

CgRenderCommand cmd = pipe.acquireCommand();
cmd.modelMatrix.translation(x, y, z);
cmd.worldAabb[0] = x-r; cmd.worldAabb[1] = y-r; cmd.worldAabb[2] = z-r;
cmd.worldAabb[3] = x+r; cmd.worldAabb[4] = y+r; cmd.worldAabb[5] = z+r;
cmd.queueSlot = CgRenderQueue.GEOMETRY;
cmd.mesh = myMesh;
cmd.material = myMaterial;
pipe.submit(cmd);
// MC loaders call the split API each frame (via stage events / CgRenderHook):
// pipe.executeOpaquePass(partialTicks, sourceFboId);
// pipe.executeTransparentPass();
// pipe.endFrame();
// pipe.execute(partialTicks) is a convenience wrapper for harness / single-hook paths only
```

## Invariants

- `worldAabb` is initialised to `NaN` by `reset()` — `submit()` throws if not set
- `normalMatrix` is always derived by `submit()` — do not pre-set
- `OVERLAY` queue slot is rejected by `submit()` in Phase 1 MVP
- Pool never returns null; grows with `Arrays.copyOf` on exhaustion
- `CgRenderPipeline.destroy()` is called by `CgGraphicsLifecycle.destroyContext()`
