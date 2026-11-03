# loading — the loading screen, and what loads behind it

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

Lyra's `ULoadingScreenManager`, ported: one screen, shown while anything loads, then held until the engine stops doing
first-use work. What loads behind it is orchestrated and tracked here too (plan `vfx-aaa/steps/A15.md`): Forge's
parallel mod loading with its `enqueueWork`, Unreal's slow-task progress and its game-thread hitch heartbeat.

| Package / type | Role |
|------|------|
| `CgLoadingScreen` | The state machine: LOADING (a process answers a reason, or a plan task runs), HOLDING (none does; `holdFrames` quiet frames of `CgPipeline.misses`, `compileWaits`, texture loads and sync asset loads, at most `maxHoldSeconds`), HIDDEN. Starts LOADING at launch. `frame()` runs in `CgGraphicsLifecycle.tickFrame`; a host calls `draw(w, h)` last each frame, and reads `loading()` to skip its world |
| `CgLoadingProcess` | Lyra's `ILoadingProcessInterface`: asked each frame for a reason, or null once done; `progress()` for its share. Each reason given becomes a tracked task (`loading:<reason>`, the engine's `crystalgraphics:<reason>`) |
| `CgLoadingScreen.Task` | Lyra's `ULoadingProcessTask`: work started and ended in code (`task(reason)` ... `close()`) |
| `task/` | `CgLoadPlan` (named tasks: `task(id).after(...).work(...).render(step).start()`, `track(id, reply)`, `begin(id)`; listeners get it in `onLoad`; its `frame()` runs in the frame tick's host section, starting tasks whose `after` ended and running render parts within `crystalgraphics.loading.renderBudget`), `CgLoadTask` (one task: id `<owner>:<name>`, state, `after`, start and end, reported progress and detail), `CgLoadStep` (a render part, a slice a call), `CgLoadTracker` (every task of this load: `running()`, `progress(now)` for the bar, history per `key`, the timeline logged at the hide) |
| `watchdog/` | `CgLoadWatchdog`: Unreal's `FGameThreadHitchHeartBeatThreaded`. The frame beats; a thread polls every 8 ms, samples the render thread's stack each poll once a frame is held past `crystalgraphics.loading.stallMs` (100), and reports the hold when it ends: how long, the render thread's CPU time over it, and where its samples landed (the lifecycle callback, else the first stack frame outside the JDK and the engine's plumbing). A hold whose samples sit mostly in a GL call, a swap, a fence or a sleep is a **wait** (the driver or GPU catching up), and so is one where the render thread barely ran and was in no read (the machine had it descheduled); any other is **work**, a read from disk included. A pipeline built or linked at its draw (`vkCreate*Pipelines`, the tracker's `takeAhead`, a link wait) is work named as one to precache, never a wait. The hide logs both counts; `loading.strict` fails the run when work held it |
| `view/` | `CgLoadingView` / `CgLogoLoadingView`: what it draws: the engine's lockup (`textures/gui/lockup.png`) over a view up at the ocean's surface (`shaders/loading/loading_fx.shader`), Minecraft's white outlined bar under it; under `crystalgraphics.loading.showTasks` the running tasks a line each under the bar, owner, name, percent (a system sans-serif). `warm` starts its lockup decoding on a worker at the graphics start; `ready` answers once that and its shader's pipeline are built |

## Rules

- **Loading is named, never done in a callback.** A listener's `onLoad(plan)` names tasks and returns; work goes on
  workers (`work`), GL in slices (`render`). Lifecycle callbacks are timed and marked for the watchdog: one over 50 ms
  while the screen shows is logged with the listener's name.
- **Kits name themselves**: `CgAssetRegistry.scanAsync` is `<namespace>:scan` (its progress the YAML files listed
  against those the last scan read there, then the reads), `CgVfxAssets.load` is `<namespace>:load` (its handles'
  progress by bytes, ended once every asset settled), and the engine's start is `crystalgraphics:start`.
- **A task that can report progress does** (`task.progress(f)`, any thread): the bar trusts it over last load's time,
  which a busy machine or a cold cache can double.
  The timeline at the hide shows what overlapped: tasks whose spans overlap ran in parallel.
- **The bar is time done over time expected** (`CgLoadTracker.progress`): ended tasks count what they took, waiting ones
  and those of the last load not seen yet what they took then, running ones their time so far against the whole their
  own progress implies, or with none their last time (once past it, their time so far plus the overrun again: the bar
  holds rather than racing). The host keys the history (`CgLoadTracker.key`: the harness's scene).
- **What has no progress of its own is scaled by this load's pace** (`CgLoadTracker.pace`): what the ended tasks took,
  the engine's start among them, and what the reporting ones took for their share, against last load's times, held to a
  third and three times. A busy machine slows every task alike. A task's own progress is trusted no further than its
  share of the time expected, since a total still growing reads half done at once. Each kept time is averaged with the
  one before, so one cold or busy load moves the next one's bar halfway.
- **The engine's processes (pipelines, assets, effects warming) and plan tasks keep a showing screen up but never raise
  a hidden one.** A look precached at runtime spreads under the warm budget instead. Only `add`/`task` raise it.
- **Warming advances only where a world stage fires** (`CgWorldRenderer.settle`, the world schedule's effects), so
  `draw` fires the world stages itself, into a 256-pixel offscreen target under a fixed camera, on a frame where the
  host fired none. A host draws nothing of its own world while loading and calls `draw` inside its frame, never from
  the frame tick: a world firing from `tickFrame` lands between the effects' steps.
- **While shown, warming takes `crystalgraphics.warmBudget.loading`** (12 ms a frame: the screen near 60 fps) through `CgWarmBudget.loading`.
- **In Minecraft it shows once, between the loader's screen and the first menu** (the user, 2026-11-02): each host
  hands the game's opening of its first screen to `holdUntilHidden` and puts up an empty screen, since Minecraft with
  no screen and no level reads keys for a player it has not made. The host draws it over the GUI at its frame end and
  runs the held opening once it has hidden and faded out, so the title screen and Quick Play follow as they were. After
  the fade, not at the hide: Quick Play blocks the client thread seconds reading the world, and the frame left up
  meanwhile would be ours, frozen at its end; that freeze is vanilla's to show (the user, 2026-11-03). Nothing raises it after
  that, in menus or the world. Each era's hook: `docs/MINECRAFT_INTEGRATION.md` § *The loading screen*.
- **Anything that stands the engine down hides it** (`hideNow`), so a held opening never waits forever.
- **The screen draws nothing of its own until its view is `ready()`** (its shaders built, its textures loaded): the
  host's own loading screen stays up meanwhile, and the fade-in starts at the first frame drawn whole. Its first frame
  built `loading_fx` at the draw and waited half a second on its pipelines (26.2, Vulkan). The view is warmed first in
  the graphics start, before the engine's programs queue, and asked from then on at each frame and draw.
- **The screen draws from the engine's start, over the host's own loading screen** (`hostDraws()` while shown), so
  its fade-in crosses from that screen. Once the host's first screen has opened (Minecraft's menu panorama), a view not
  yet ready is finished at the draw (`finish()`, the frame held rather than the menu shown), and the fade-in starts
  half done: there is no loading screen left to cross from. Minecraft 26.2 opens its first screen right after its
  language load, which can hold the render thread 2 s just after the engine starts.
- **The fades run on frames, not the clock**: each frame moves them a 30th of a second at most, so a held frame shows
  every stage of them rather than skipping to the end. The fade-out is over 5 s after the hide whatever was drawn, so
  a host that stops drawing still gets its opening.
- **The world frame the screen fires draws as the host's world does** (`hostWorld(depth, reversed, zeroToOne)`, from
  the host before its first draw): on a Vulkan device a pipeline is built for its target's formats and depth test, and
  one built for a target the world never uses is a compile wasted on the render thread. Minecraft 26.2+ passes
  `DEPTH32F`, reversed, zero to one.
- Every change of reason is logged (`[loading] ...`); `crystalgraphics.loading.logProgress` logs the bar and the running
  tasks once a second. Its frames carry the counter `loading.shown` and the markers `loading.show`/`loading.hide`, so a
  profile tells loading from steady state.
