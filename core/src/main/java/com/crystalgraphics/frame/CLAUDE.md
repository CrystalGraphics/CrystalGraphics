# frame — the world's tick schedule

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

What runs once a frame, in order. Usage and examples: `docs/ENGINE_API.md` § *The world and its schedule*.

| Type | Role |
|---|---|
| `CgWorld` | One level's schedule and subsystems; `client()` is the client's, run by `CgWorldRenderer.beginFrame` |
| `CgTickGroup` | The ordered phases; each is a trace zone `frame.<group>` |
| `CgTickFunction` | One named body in a group, with prerequisites, interval, pause and server flags |
| `CgTickSchedule` | Plans (effective group, then Kahn's per group, ties by name) and runs; disables a throwing function |
| `CgWorldSubsystem` | State with a lifetime that registers functions |

- **Order is never registration order.** Ties break by name, so two mods cannot reorder each other by load order.
- **The plan is rebuilt on any add, close or `after`**, not per frame; the frame path allocates nothing.
- **Draws stay stage renderers** (`CgRenderStage`); the world groups run at the top of the first `WORLD_OPAQUE` firing.
- Headless: nothing here touches GL, so tests run on `core/src/headlessTest/`.
