# loading — the loading screen

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

Lyra's `ULoadingScreenManager`, ported: one screen, shown while anything loads, then held until the engine stops doing
first-use work.

| Type | Role |
|------|------|
| `CgLoadingScreen` | The state machine: LOADING (a process answers a reason), HOLDING (none does; `holdFrames` quiet frames of `CgPipeline.misses`, `compileWaits`, texture loads and sync asset loads, at most `maxHoldSeconds`), HIDDEN. Starts LOADING at launch. `frame()` runs in `CgGraphicsLifecycle.tickFrame`; a host calls `draw(w, h)` last each frame, and reads `loading()` to skip its world |
| `CgLoadingProcess` | Lyra's `ILoadingProcessInterface`: asked each frame for a reason, or null once done; `progress()` for the bar |
| `CgLoadingScreen.Task` | Lyra's `ULoadingProcessTask`: work started and ended in code (`task(reason)` ... `close()`) |
| `CgLoadingView` / `CgLogoLoadingView` | What it draws: the engine's lockup (`textures/gui/lockup.png`, the logo and "Crystal Graphics") over a view up at the ocean's surface (`shaders/loading/loading_fx.shader`), Minecraft's white outlined bar under it. No text: what loads is in the log. `warm` loads its texture at the graphics start (`CgLoadingScreen.warm`, which `install` calls) |

## Rules

- **The engine's processes (pipelines, assets, effects warming) keep a showing screen up but never raise a hidden
  one.** A look precached at runtime spreads under the warm budget instead. Only `add`/`task` raise it.
- **Warming advances only where a world stage fires** (`CgWorldRenderer.settle`, the world schedule's effects), so
  `draw` fires the world stages itself, into a 256-pixel offscreen target under a fixed camera, on a frame where the
  host fired none. A host draws nothing of its own world while loading and calls `draw` inside its frame, never from
  the frame tick: a world firing from `tickFrame` lands between the effects' steps.
- **While shown, warming takes `crystalgraphics.warmBudget.loading`** (12 ms a frame: the screen near 60 fps) through `CgWarmBudget.loading`.
- **In Minecraft it shows once, between the loader's screen and the first menu** (the user, 2026-11-02): each host
  hands the game's opening of its first screen to `holdUntilHidden` and puts up an empty screen, since Minecraft with
  no screen and no level reads keys for a player it has not made. The host draws it over the GUI at its frame end and
  runs the held opening once it hides, so the title screen and Quick Play follow as they were. Nothing raises it after
  that, in menus or the world. Each era's hook: `docs/MINECRAFT_INTEGRATION.md` § *The loading screen*.
- **Anything that stands the engine down hides it** (`hideNow`), so a held opening never waits forever.
- Every change of reason is logged (`[loading] ...`); its frames carry the counter `loading.shown` and the markers
  `loading.show`/`loading.hide`, so a profile tells loading from steady state.
