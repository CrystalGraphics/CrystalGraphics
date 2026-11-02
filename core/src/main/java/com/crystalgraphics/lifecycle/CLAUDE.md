# lifecycle — the engine's lifecycle

`CgEngineLifecycle` is the engine's lifecycle and the one place a host starts and stops it. On both sides, in pre-init,
init and post-init: pre-init mounts every mod's assets (`asset.load.CgAssetMounts`), init installs the network
(`net.CgNetwork`), post-init (the engine's own, at its first client graphics start or server start) loads what was
mounted. Then the graphics context: `ensureGraphicsContext` (the one way to start it) and `shutdown` (the one way to stop it: it frees
the context on the thread that owns it, then stands the engine down) issue `CgGraphicsLifecycle`'s, whose own versions
are not public. `stopped()` writes the trace run's exit files (`trace.CgTraceRun`).

- **Core's starting work goes here**, never into a host: a host registers the platform, fills its slots, and calls the
  phases (`docs/MINECRAFT_INTEGRATION.md`).
- **The game's ticks and the server's start and stop are the engine's**: `serverStarting`, `serverTick`,
  `serverStopping`, `clientTick`, which hosts call, and `onServerTick`/`onClientTick`, which a subsystem subscribes
  to (never `CgNetwork`). Each tick runs its listeners, then the network's share; the client's runs with no
  connection too.
- `CgGraphicsLifecycle` does the graphics work: what a context builds, the teardown order, and the two calls a host
  makes on it directly, `tickFrame` and `onResize` (through `CgLifecycleService`).
- On a client, post-init follows the graphics start: what loads is precached by the effects it installs.
- **One listener registry, `CgEngineLifecycle`'s.** Code outside the engine hears every moment through a
  `CgLifecycleListener` added with `CgEngineLifecycle.addListener`: `onPreInit`/`onInit`/`onPostInit`, then
  `onContextInit`/`onLoad`/`onFrame`/`onContextDestroy`/`onShutdown`. A late listener is caught up, so each fires once, and a
  phase reaches listeners after the engine's own work in it.
- **What loads behind the loading screen is named in `onLoad(CgLoadPlan)`** (client, after the graphics start and
  post-init), never done in a callback: every dispatch is timed and marked for `loading.watchdog.CgLoadWatchdog`, and
  one over 50 ms while the screen shows is logged with the listener's name. The graphics start is the tracked task
  `crystalgraphics:start`; the plan steps in `tickFrame`'s host section (`loading/CLAUDE.md`).
- **The graphics start is short, and logs its steps** (`CgStartSteps`: one `[start]` line, each step's ms): the screen
  cannot draw until it ends. Its GL-free work runs ahead on a worker (`CgEngineLifecycle.clientStarting`, which a
  client host calls at its install; else at the start itself), joined before the first install that may reach the same
  classes, since two threads initializing classes that name each other deadlock. Never a texture ahead: until the
  render thread's first GL call claims the context, `CgGL.mayIssueGl` passes on any thread. The engine's programs build
  behind the screen as the task `crystalgraphics:programs` (`CgWorldRenderer.beginBoot`/`bootStep`), and the screen
  fires no world stage until they have.
- `CgGraphicsLifecycle` holds no listeners: it signals each context moment to `CgEngineLifecycle` (package-private
  `contextInitialized`, `frame`, `contextDestroying`, `stopped`) at the point it belongs in its work, and the engine
  dispatches.
