# lifecycle — the engine's lifecycle

`CgEngineLifecycle` is the engine's lifecycle and the one place a host starts and stops it. On both sides, in pre-init,
init and post-init: pre-init mounts every mod's assets (`asset.load.CgAssetMounts`), init installs the network
(`net.CgNetwork`), post-init (the engine's own, at its first client graphics start or server start) loads what was
mounted. Then the graphics context: `ensureGraphicsContext` (the one way to start it), `destroyContext` and `shutdown` issue
`CgGraphicsLifecycle`'s, whose own versions are not public.

- **Core's starting work goes here**, never into a host: a host registers the platform, fills its slots, and calls the
  phases (`docs/MINECRAFT_INTEGRATION.md`).
- `CgGraphicsLifecycle` does the graphics work: what a context builds, the teardown order, and the two calls a host
  makes on it directly, `tickFrame` and `onResize` (through `CgLifecycleService`).
- On a client, post-init follows the graphics start: what loads is prepared by the effects it installs.
- **One listener registry, `CgEngineLifecycle`'s.** Code outside the engine hears every moment through a
  `CgLifecycleListener` added with `CgEngineLifecycle.addListener`: `onPreInit`/`onInit`/`onPostInit`, then
  `onContextInit`/`onFrame`/`onContextDestroy`/`onShutdown`. A late listener is caught up, so each fires once, and a
  phase reaches listeners after the engine's own work in it.
- `CgGraphicsLifecycle` holds no listeners: it signals each context moment to `CgEngineLifecycle` (package-private
  `contextInitialized`, `frame`, `contextDestroying`, `stopped`) at the point it belongs in its work, and the engine
  dispatches.
