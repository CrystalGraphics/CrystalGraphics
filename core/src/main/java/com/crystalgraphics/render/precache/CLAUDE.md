# render/precache — pipelines built ahead of use

> Root guide: [`CrystalGraphics/docs/PRECACHE.md`](../../../../../../../docs/PRECACHE.md)

| Type | Role |
|------|------|
| `CgPipelinePrecache` | The one way to build pipelines ahead of use (Unreal's PSO precaching): `register(name, collector)` at load, collected and built at the graphics start inside the loading screen (one registered later at the next world frame, over frames); `request(collector)` at runtime, polled with `Request.ready()`; `steps(name).ahead(work).step(c)...start()` a request too big for a frame, its worker phase first, then each step collected while `CgWarmBudget` lasts. Every warm-up that holds something until built (VFX looks and kernels, post volume looks, demo and harness props) is a request; renderers' own draw-time holds (`SKIP_UNREADY`, `CgPostMaterial`) stay theirs |
| `CgPipelineList` | What every collector named, kept built for the session: materials (`add(material, half[, skinned])`, manifests, shader keyword sets through a probe material), renderer passes (`addPass`: the Forward program alone) and kernels (`add(kernel)`, `kernels(preparer)`). Polled each frame inside `CgWarmBudget`; a settings change (HDR, merge, joins, half size) rebuilds every material under the new settings before it applies, never holding it past `MAX_WAIT_FRAMES`. A request polls only its own entries |
