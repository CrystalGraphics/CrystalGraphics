# api/render — CrystalGraphics Render Pipeline Public API

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../../../AGENTS.md)

## What This Package Is

Public contracts for the CrystalGraphics forward render pipeline. Game code uses these
types to submit geometry for rendering each frame.

## Class Map

| Type | Role |
|------|------|
| `CgFrameData` | Per-frame mutable data bundle: `viewMatrix`, `projMatrix`, `timeSecs`, `viewportW/H`, `cameraPos`, `cameraForward`, `farPlane`, `worldTimeSampler`. Call `deriveFromViewMatrix()` to populate `cameraForward` and `cameraPos` from `viewMatrix`. Exposed by `CgRenderPipeline.getFrameData()`. |
| `CgRenderCommand` | One render submission: mesh + material + transform + AABB + sort metadata. Pre-allocated in `CgRenderCommandPool` — never instantiate directly. Set `worldAabb[0..5]` before `submit()`. |
| `CgRenderCommandPool` | Pre-allocated pool of `CgRenderCommand` slots. `acquire()` resets and returns a slot; `release(cmd)` is idempotent; `releaseAll()` is O(N) bulk-release used at frame-end. Grows with `Arrays.copyOf` on exhaustion. |
| `CgSortKey` | Static utility: encodes 64-bit sort keys for opaque (front-to-back, material-grouped) and transparent (back-to-front) commands. No instances. |
| `CgRenderCommandQueue` | Frame submission queue. `submit(cmd)` validates AABB, derives `normalMatrix`, computes `cameraDepth` + `passFlags` + `sortKey`. `sort()` indirect-quicksorts and builds `sortedOpaque[]` + `sortedTransparent[]` filtered views. `releaseAll()` resets all counts and delegates to pool. |
| `CgRenderPipeline` | Singleton pipeline orchestrator. Owns the per-frame UBO and per-object SSBO/TBO. `acquireCommand()` / `submit(cmd)` are the game-code entry points each frame. `executeOpaquePass(partialTicks)` runs sort + UBO upload + forward pass. `executeTransparentPass()` runs transparent back-to-front pass. `endFrame()` releases pool. `execute(partialTicks)` is the single-call convenience wrapper. `getFrameData()` returns the mutable per-frame data holder. `init()` / `destroy()` manage the singleton lifecycle. |

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
// CgRenderPipeline.execute(partialTicks) is called by CgRenderHook each frame
```

## Invariants

- `worldAabb` is initialised to `NaN` by `reset()` — `submit()` throws if not set
- `normalMatrix` is always derived by `submit()` — do not pre-set
- `OVERLAY` queue slot is rejected by `submit()` in Phase 1 MVP
- Pool never returns null; grows with `Arrays.copyOf` on exhaustion
- `CgRenderPipeline.destroy()` is called by `CgGraphicsLifecycle.destroyContext()`
