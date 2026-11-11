# Loading: what loads when, and how frames stay smooth

Read this before your mod or engine code loads anything: a file, a texture, a mesh, a shader, a kit, a font, an index.
It says **when** each kind of thing should load, **where** it runs, and what keeps the frame smooth while it does,
both behind the loading screen and while the player plays.

There are two frame budgets, and loading must respect both:

| Frame | Budget | What a missed budget looks like |
|---|---|---|
| **The loading screen's** | the screen animates at the host's frame rate; no single frame held past 100 ms | the logo stutters or the bar freezes, and the watchdog names you in the log |
| **Gameplay** | the player's frame (16.7 ms at 60 fps, 3.3 ms at 300) | a hitch the moment something first appears: a move, a texture, a menu |

The approach behind both, taken from Unreal and Forge: **name the work, run it on workers, and give the render
thread only device calls, in slices that fit the frame.** The render thread is never handed a big piece of work it
must finish before it can draw.

---

## 1. When does it load?

Pick the first row that fits.

| What you know | When it loads | Where | API |
|---|---|---|---|
| A pipeline every session draws (your material, kernel, pass) | registered at mod load, built at the graphics start | the shader compile workers | `CgPipelinePrecache.register` in pre-init or init ([`PRECACHE.md`](PRECACHE.md)) |
| A VFX look your mod plays | named at mod load, built and rehearsed at the graphics start | as above | `VFX.precache(LOOK)` (a `CgVfxOwner`), or a cue / `CgVfxRegistry`: automatic |
| Assets of the format your gameplay plays: cues, moves (sequences), a kit's effects | behind the loading screen, with all they reach | the asset workers, GPU work in the warm budget | **automatic: every mounted namespace loads whole.** A namespace of thousands of assets played in part (a game's full asset export) opts out with `all: false` in `assets/<ns>/preload.yaml` and names what it plays ([`vfx/assets.md`](vfx/assets.md) § *Shipping them in your mod*); an ordinary mod needs nothing |
| Files every session needs: an atlas, an index, a model set | behind the loading screen | job workers, GL in budgeted slices | a task in `onLoad(CgLoadPlan)` (§3) |
| GPU objects sized to the window (a framebuffer, a target) | at the graphics start | render thread, cheap creates only | `onContextInit(w, h)`: create, never fill |
| Something a gameplay moment needs (an arena, a move, a pack) | as soon as the moment is known, ahead of its first frame | workers, then the warm budget | `CgPipelinePrecache.request`, `CgPreload.request`, `VFX.precache`, then hold until `ready()` (§5) |
| Work beside the game with an answer later (a search, a bake) | whenever asked | a job worker, answered on the asking thread | `CgJob` ([`JOBS.md`](JOBS.md)) |
| Anything at its first draw | **never** | — | — |

**Earlier is cheaper.** A pipeline registered at init costs nothing the player sees; the same pipeline built at its
first draw costs 7-110 ms on GL, and on Vulkan a frame stall.

---

## 2. A client's start, in order

```
mod loading          clientStarting: GL-free work starts on a worker (the engine's class initializers, text
                     natives, CrystalGUI's styles and themes as `crystalgui:start`)
pre-init / init      assets mounted, network installed, CgPipelinePrecache collectors registered
graphics start       a stage a frame, the host's frames going on between:
                       context -> targets -> loading view -> world renderer -> post -> VFX -> the rest
                     onContextInit(w, h)
post-init            the engine's own, on a client right after the graphics start: what was mounted loads
                     onLoad(plan)                      <- you name your tasks here
loading screen       LOADING while any task or process runs; the engine's programs build (`crystalgraphics:programs`)
                     HOLDING  30 quiet frames with no first-use work (at most 10 s)
                     HIDDEN   fade-out, then the host's first screen opens (Minecraft's title screen, Quick Play)
gameplay             what is asked later loads under the warm budget, never raising the screen by itself
```

- **The graphics start is short and staged** (`CgStartSteps` logs one `[start]` line with each step's ms). A step
  that costs tens of ms gets a stage of its own (`lifecycle/CLAUDE.md`).
- **The loading screen draws over the host's own loading screen** from the engine's start, and crossfades from it. Until
  its own shaders and lockup are ready, it shows its backdrop's gradient (`CgLoadingView.cover`, clears only, no program).
- **In Minecraft it shows once**, between the loader's screen and the first menu. The engine never raises it again;
  only a mod's `CgLoadingScreen.add`/`task` does (§5, *Covering the game*).
- **A dedicated server** has no screen and no plan: its loading stays in its pre-init, init and post-init phases.

### What already loads behind the screen

You do nothing for these; they are tasks or processes the bar counts.

| Task | What |
|---|---|
| `crystalgraphics:start` | the graphics start, its stages |
| `crystalgraphics:programs` | the engine's own programs and pipelines (world renderer, post, bloom, text, the loading view's) |
| `crystalgraphics:noise` | the engine's noise volumes and wavelet tile, baked by kernels once they are built (`CgNoiseVolumes`) |
| `crystalgraphics:<reason>` | the engine's processes: pipelines building, VFX looks warming, asset loads |
| `<namespace>:scan`, `<namespace>:load` | a namespace's YAML scan, then its assets: all of each namespace unless its `preload.yaml` says `all: false`, and each `preload.yaml`'s declarations, with what they reach (`CgAssetMounts.loadAhead`) |
| `crystalgui:start`, `crystalgui:paint`, `crystalgui:desktop` | CrystalGUI's styles, themes and widget kinds on a worker; then its fonts and paint shaders; then its desktop, built and laid out |
| `loading:<reason>` | each reason a `CgLoadingProcess` gives |

---

## 3. Behind the loading screen: the load plan

A mod declares its listener in `META-INF/crystalgraphics/listeners` ([`ENGINE_API.md`](ENGINE_API.md) § *Hearing the
engine's lifecycle*), and names what it loads in `onLoad`:

```java
public final class MyClient implements CgLifecycleListener {
    public static final MyClient INSTANCE = new MyClient();

    @Override public void onLoad(CgLoadPlan plan) {                           // render thread: name tasks, return
        plan.task("mymod:icons")
                .work(ctx -> Icons.decodeAll(ctx))                            // a job worker: files, decoding, no GL
                .render(task -> Icons.uploadNext())                           // render thread, one slice a call
                .start();
        plan.task("mymod:recipes").after("mymod:icons").work(Recipes::index).start();
        plan.track("mymod:index", CgJob.run(() -> Search.buildIndex()));             // work already running
    }
}
```

- **`work`** runs on a job worker: read files, decode, parse, build meshes (meshes are data on any thread), make and
  fill textures (they queue through `CgDeferral` and land before the next frame).
- **`render`** is a `CgLoadStep`: one slice of render-thread work a call, `true` once all done. The plan runs slices
  while `crystalgraphics.loading.renderBudget` (6 ms) lasts, and always one. **Keep one slice at 1-3 ms**: one upload,
  one object, one batch. A slice cannot be cut once it starts. **A slice that only waits** (a worker, a compile, the
  next frame) calls `task.nextFrame()` before returning false, so it is called once a frame rather than spinning out
  the budget.
- **`after`** orders tasks, of any owner. Tasks with no `after` run in parallel. A failed task fails every task after it.
- **`track`** adopts a `CgReply` already started (a scan, a download, a `CgJob`). **`begin`** is work started and ended
  in code, closed with try-with-resources:

```java
try (CgLoadPlan.Span arena = CgLoadPlan.get().begin("mymod:arena")) {
    arena.task().progress(0.5f);
}
```

### Report progress

The bar is time done over time expected, weighed by each task's time in the last load (kept in the cache directory).
A task that reports its own progress is trusted over that history, which a cold cache or a busy machine can double.

```java
plan.task("mymod:models").work(ctx -> {
    ctx.progress().begin("models", files.size());
    int done = 0;
    for (Path f : files) { read(f); ctx.progress().advance(++done); }   // units done so far, not a step
    return null;
}).start();

task.progress(done / (float) total);      // a CgLoadTask from track or begin, any thread
task.detail("Goku's mesh");               // shown beside its name with showTasks
```

- **Ids are `<owner>:<name>` and the same every run**: the history is keyed by id. An id built from a counter or a time
  has no history, so its share of the bar is a guess.
- A task that cannot report progress still works; the bar scales it by this load's pace.

---

## 4. Keeping the loading screen smooth

Every rule here comes from a hold the watchdog found. While the screen shows, the render thread beats once a frame;
a frame held past `crystalgraphics.loading.stallMs` (100 ms) is sampled and classed as **work** (your code ran) or a
**wait** (a driver call, swap, fence or sleep). Each hold is logged in a dev environment, the harness and prodSmoke
(`crystalgraphics.loading.logHolds` elsewhere); a player's log keeps only the counts and the timeline at the hide.

**Never on the render thread while loading:**

| Don't | Do |
|---|---|
| work inside a lifecycle callback (`onContextInit`, `onLoad`, `onPostInit`...): each is timed, and one over 50 ms is logged by name | name a task; create objects in `onContextInit`, fill them in a task |
| read files (`CgIO`, `Files.read*`, a resource stream) | `work` on a worker |
| parse many `.shader` / `.compute` files in a collector | `CgPipelinePrecache.parseAhead(...)` from `onContextInit`; `CgKernel.ahead(MyOps::precache)` for kernels |
| compile a shader at its draw | precache it: the compile runs on a worker (`CgShaderCompile`) and its pipelines are built ahead |
| upload a big texture in one call | make and fill it on a worker (it lands through `CgDeferral`, at most 4 ms and 4 MB a frame), or upload a slice per `render` step |
| query a program you just linked (`glGetUniformLocation`, `glGetProgramResourceIndex`) | let the material resolve uniforms; a query forces the link to finish now |
| initialise a heavy class graph (a big static initializer, a font, a registry) | touch it from a worker first, and join before the render thread uses it (below) |
| wait for a job (`join`, `get`, a spin) | `then`, or `after` between tasks |

**Threads, carefully:**

- **Device work off the render thread waits for it.** A texture made on a worker defers its uploads (`CgDeferral`):
  `CgRHI.onDeviceThread()` is false on every thread before the device starts, and on every thread but its own after.
- **Class initialization ahead deadlocks if two threads meet.** Two threads initialising classes that name each other
  wait on each other forever. Warm a class graph on one worker, and join it before the render thread can reach the same
  classes (the engine's `clientStarting` work is joined before the first install that may reach it).
- **Keep listener classes cheap to load**: they load as the engine starts, before most of the game.

**Shaders.** shaderc compiles on job workers (`CgShaderCompile`) and pipelines come from the recorded list
([`PRECACHE.md`](PRECACHE.md) § *The shipped pipeline list*); before the toolchain lands, a GL-era host's native compiler
compiles on the render thread. Either way: **what is not precached compiles in front of the player.**

**Frame rate.** The screen draws in the host's frame, at the host's rate. 1.7.10 keeps vanilla's 30 fps menu cap while
loading: above it the driver's waits on `glGet` grew (the longest hold 142 ms at 30 fps, 322 ms at 60, 435 ms
uncapped). Don't raise a host's frame rate during loading.

### What a load looks like (development machine, NVIDIA, 2026-11-03)

Holds are frames over 17 ms while the screen shows. Cold is no shader caches.

| Host | Holds | Total held | Longest |
|---|---|---|---|
| Forge 1.20.1, cold | 10 | 391 ms | 72 ms |
| Forge 1.20.1, warm | 23 | 1069 ms | 114 ms |
| Forge 1.7.10, cold | 20-52 | 1.2-2.8 s | 206-328 ms |
| Forge 1.7.10, warm | 30 | 1212 ms | 142 ms |
| NeoForge 26.2, Vulkan | 9 | 287 ms | 54 ms |

What remains on 1.7.10 is the driver's `glGet` waits (no state cache) and Java's class loading: neither is a loader's
work to move. A mod whose task shows up in a hold over 100 ms has work to move.

---

## 5. Gameplay frames: loading while the player plays

Nothing raises the screen during play. Everything here spreads over frames under a budget, and **whatever needs it
waits for `ready()` instead of drawing late or stalling the frame.**

### Pipelines and looks: request, then hold

```java
CgPipelinePrecache.Request arena = CgPipelinePrecache.request(p -> {
    p.add(FLOOR, false);
    p.add(ARMOUR, false, true);                     // a material a skinned mesh draws
});
CgPreload.Request files = CgPreload.request(l -> l.texture(FLOOR_TEXTURE).mesh("mymod:models/arena.glb")
        .font(() -> CgDefaultFont.bold(24), 24, 48));    // a title font, its ASCII at the sizes it draws

if (arena.ready() && files.ready()) openArena();   // each frame until both are
```

```java
VFX.precache(SPIRAL, SHOCKWAVE);                   // a move known now, played in a second or two
if (!VFX.ready()) return;                          // each frame until it is
```

- Requests build within `crystalgraphics.warmBudget` (4 ms a frame; 12 ms while the screen shows). Your own warming
  asks the same budget:

```java
if (!CgWarmBudget.allows()) return false;          // this frame's share is spent: next frame
long t = System.nanoTime();
kernel.precache();
CgWarmBudget.spend(System.nanoTime() - t);
```

- Too much for one frame, with CPU-only setup? `CgPipelinePrecache.steps(id).ahead(...).step(...)...start()`: the
  `ahead` part on a worker, then a step while the budget lasts ([`PRECACHE.md`](PRECACHE.md)).
- **Ask as early as the moment is known**: when the player picks the move, not when it fires; when the arena is
  chosen, not when it opens. A request a second ahead is invisible; one a frame ahead is a hitch.

### Data and uploads

- **Files and decoding on workers**: `CgJob.lane(CgJobLane.BACKGROUND)` for streaming nobody waits on, `LATENCY` (the
  default) for what shows a few frames later. The answer comes back in `then`, on your thread. Never block on it.
- **Textures from any thread**, through `CgDeferral`: at most `crystalgraphics.deferral.budget` (4 ms) and
  `.megabytes` (4 MB) land a frame. Write big uploads straight into a lease (`CgUploads.lease`) so no thread copies
  them twice. On Vulkan a new texture's uploads run on the transfer queue beside the frame
  ([`ENGINE_API.md`](ENGINE_API.md) § *Async*).
- **Meshes are data**: build and edit them on any thread; the mesh store places them.
- **Keep GPU resources cached.** Releasing a texture or mesh when a move ends and re-uploading it on the next costs the
  upload again each time.

### First plays

A precached look still runs cold Java the first time: classes load, the JIT has not compiled its paths. The engine
rehearses every precached look in a hidden system before its first play ([`vfx/performance.md`](vfx/performance.md)
§ *First plays*). Code of your own with a heavy first run warms the same way: run it once on a throwaway input
behind the screen, as a task.

### Covering the game

`CgLoadingScreen.add(process)` and `CgLoadingScreen.task(reason)` raise the screen again, in any host, and it covers
the game until they finish. Use it only where the player expects a loading screen: a join, a dimension or arena swap.
Never use it to hide streaming that the warm budget can spread.

```java
try (CgLoadingScreen.Task joining = CgLoadingScreen.task("joining the arena")) { ... }   // or close() later
CgLoadingScreen.add(() -> arena.ready() ? null : "building the arena");                 // asked every frame
```

---

## 6. Checking your load

| Flag | Use it to |
|---|---|
| `-Dcrystalgraphics.loading.stallMs=100` | the watchdog's threshold (0 turns it off); each hold is logged with its CPU time and where it was |
| `-Dcrystalgraphics.loading.logHolds=true` | log each hold on a player's client; always on in a dev environment, the harness and prodSmoke |
| `-Dcrystalgraphics.loading.strict=true` | fail the run at the hide when work held the render thread: a gate for CI or a harness |
| `-Dcrystalgraphics.loading.showTasks=true` | list the running tasks under the bar, owner, name and percent |
| `-Dcrystalgraphics.loading.logProgress=true` | log the bar and running tasks once a second |
| `-Dcrystalgraphics.pipeline.logMisses=true` | log every pipeline drawn that nothing precached |
| (always on) `asset.unplanned-loads` | an asset loaded after the loading screen hid, compiled in game time: logged by id with what reached it; declare it in `preload.yaml` |
| `-Dcrystalgraphics.loading.screen=never` / `always` | load with no screen (to measure the load itself), or keep it up to look at it |

**Read in the log:**

- `[start]`: the graphics start's steps, in ms. The screen cannot draw until the stage holding them ends.
- `[loading] ...` lines: each reason as it changes, the view's ready time, and at the hide the timeline of every task
  (spans that overlap ran in parallel) with the count of holds by work and by wait.
- A watchdog line names the lifecycle callback a hold was in, or the first stack frame outside the engine. **Work** is
  yours to move; a **wait** is the driver or GPU catching up, or the machine.
- A pipeline built or linked at its draw is reported as work, naming what to precache.

**Measure cold.** Warm runs hide compiles. A cold run deletes the engine's cache (`crystalgraphics/cache/`, its
`asset-registry.bin` included) and points NVIDIA's shader cache at an empty folder
(`__GL_SHADER_DISK_CACHE_PATH`). Profile with the trace engine ([`PROFILING.md`](PROFILING.md)): loading frames
carry the counter `loading.shown` and the markers `loading.show` / `loading.hide`.

---

## 7. Easy to get wrong

- **Doing the work in `onLoad`.** It runs on the render thread; the screen does not draw until it returns.
- **A collector registered in post-init.** On a client post-init runs after the graphics start, so its pipelines build
  over frames and draws wait meanwhile. Register in pre-init or init.
- **An `after` naming a task nobody adds.** Once every listener's `onLoad` has run, the waiting task fails.
- **A `render` slice that does everything.** It runs inside the frame; the budget cannot cut it.
- **Ids that change between runs.** The bar has no history for them.
- **Precaching the base variant only.** A draw usually binds a keyword, skinned or half-size variant: name what draws
  ([`PRECACHE.md`](PRECACHE.md)).
- **Waiting on a request by spinning.** Check `ready()` once a frame and return.
- **Releasing what will be drawn again.** Re-uploading is a hitch every time.
- **Raising the loading screen mid-game for streaming.** It covers the game; request and hold instead.

---

## Where the code is

| Package / type | Role |
|---|---|
| `loading.task.CgLoadPlan`, `CgLoadTask`, `CgLoadStep`, `CgLoadTracker` | the plan, a task, a render slice, every task of a load and the bar |
| `loading.CgLoadingScreen`, `CgLoadingProcess` | the screen's states, processes and `task`s that keep it up |
| `loading.watchdog.CgLoadWatchdog` | Unreal's game-thread hitch heartbeat: names who held the render thread |
| `lifecycle.CgEngineLifecycle`, `CgGraphicsLifecycle`, `CgStartSteps`, `CgListenerDiscovery` | the start and its stages, listeners |
| `render.world.CgPipelinePrecache`, `render.CgWarmBudget` | pipelines ahead, and the frame's share for building them |
| `asset.load.CgPreload` | textures, meshes and fonts read ahead (`font(...)` opens one and queues its ASCII glyphs) |
| `material.CgShaderCompile` | Shader compiles off the render thread |
| `gpu.CgDeferral`, `CgUploads` | textures and buffers from any thread, landed under a budget |
| `job.CgJob` | work beside the game |

Package guides: `loading/CLAUDE.md`, `lifecycle/CLAUDE.md`. The design record is plan `vfx-aaa/steps/A15.md`.
