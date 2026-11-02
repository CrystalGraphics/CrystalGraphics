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
- **Warming advances only where a world stage fires** (`CgWorldRenderer.settle`, the world schedule's effects), so
  `draw` fires the world stages itself, into a 256-pixel offscreen target under a fixed camera, on a frame where the
  host fired none. A host draws nothing of its own world while loading and calls `draw` inside its frame, never from
  the frame tick: a world firing from `tickFrame` lands between the effects' steps.
- **While shown, warming takes `crystalgraphics.warmBudget.loading`** (50 ms a frame) through `CgWarmBudget.loading`.
- **In Minecraft it shows once, between the loader's screen and the first menu** (the user, 2026-11-02): a node mixin
  at the game's opening of its first screen (`FirstScreenHook`; Forge 1.20.1 so far) hands that opening to
  `holdUntilHidden` and puts up `HeldFirstScreen`, an empty screen, since Minecraft with no screen and no level reads
  keys for a player it has not made. `LifecycleModern.frameEnd` draws the screen over the GUI and runs the held opening
  once it hides, so the title screen and Quick Play follow as they were. Nothing raises it after that, in menus or the
  world. A version not hooked yet shows nothing, and its state still runs, warming at the loading budget.
- **Anything that stands the engine down hides it** (`hideNow`), so a held opening never waits forever.
- Every change of reason is logged (`[loading] ...`); its frames carry the counter `loading.shown` and the markers
  `loading.show`/`loading.hide`, so a profile tells loading from steady state.
