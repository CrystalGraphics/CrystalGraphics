# frame — the world's tick schedule

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

What runs once a frame, in order. Usage and examples: `docs/ENGINE_API.md` § *The world and its schedule*.

| Type | Role |
|---|---|
| `CgWorld` | One level's schedule and subsystems; `client()` is the client's, run by `CgWorldRenderer` (`begin` at the camera hook, `frame` at the first opaque firing) |
| `CgTickGroup` | The ordered phases: four each game tick (`NET_IN` to `NET_OUT`, `CgWorld.tick`), eleven each frame; each is a trace zone `frame.<group>` |
| `CgTickFunction` | One named body in a group, with prerequisites, interval, pause and server flags |
| `CgTickSchedule` | Plans (effective group, then Kahn's per group, ties by name) and runs; disables a throwing function |
| `CgWorldSubsystem` | State with a lifetime that registers functions |
| `CgFrameBoundary` | The frame's end on every screen: `DRAIN` (the asset loop, edits) then `PUBLISH` (definitions swapped), from `CgGraphicsLifecycle.tickFrame`; a server's tick on a dedicated server |

- **A server's world** (`CgWorld.serverWorld()`) is ticked by `CgNetwork`'s server tick, before the network sends,
  and runs only `onServer` functions in the tick groups; no frame group runs there.
- **Order is never registration order.** Ties break by name, so two mods cannot reorder each other by load order.
- **The plan is rebuilt on any add, close or `after`**, not per frame; the frame path allocates nothing.
- **Draws stay stage renderers** (`CgRenderStage`). The client world runs in two parts: `BEGIN` through `SHAKE` from
  the host's camera hook (`CgWorldBegin`, platform), `LATE_ATTACH` and `SUBMIT` at the first `WORLD_OPAQUE` firing.
  A firing whose hook did not run takes both, counted `frame.begin-late`; the harness declares it has no hook.
- Headless: nothing here touches GL, so tests run on `core/src/headlessTest/`.
