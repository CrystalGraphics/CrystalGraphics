# loading — the loading screen

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

Lyra's `ULoadingScreenManager`, ported: one screen, shown while anything loads, then held until the engine stops doing
first-use work.

| Type | Role |
|------|------|
| `CgLoadingScreen` | The state machine: LOADING (a process answers a reason), HOLDING (none does; `holdFrames` quiet frames of `CgPipeline.misses`, `compileWaits`, texture loads and sync asset loads, at most `maxHoldSeconds`), HIDDEN. Starts LOADING at launch. `frame()` runs in `CgGraphicsLifecycle.tickFrame`; a host calls `draw(w, h)` last each frame, and reads `loading()` to skip its world |
| `CgLoadingProcess` | Lyra's `ILoadingProcessInterface`: asked each frame for a reason, or null once done; `progress()` for the bar |
| `CgLoadingScreen.Task` | Lyra's `ULoadingProcessTask`: work started and ended in code (`task(reason)` ... `close()`) |
| `CgLoadingView` / `CgLogoLoadingView` | What it draws: the engine's logo over a white outlined bar on indigo, laid out as Minecraft's loading overlay |

## Rules

- **The engine's processes (pipelines, assets, effects warming) keep a showing screen up but never raise a hidden
  one.** A look precached at runtime spreads under the warm budget instead. Only `add`/`task` raise it.
- **Warming advances only where a world stage fires** (`CgWorldRenderer.settle`, the world schedule's effects). A host
  that hides its world while loading still fires the world stages (the harness: `HarnessWorld.fire` with no scene
  content), or the screen waits on work that never runs, until `maxSeconds`.
- **While shown, warming takes `crystalgraphics.warmBudget.loading`** (50 ms a frame) through `CgWarmBudget.loading`.
- **No Minecraft host draws it**: Minecraft has its own screens. The state still runs there, so warming takes the
  loading budget until it hides.
- Every change of reason is logged (`[loading] ...`); its frames carry the counter `loading.shown` and the markers
  `loading.show`/`loading.hide`, so a profile tells loading from steady state.
