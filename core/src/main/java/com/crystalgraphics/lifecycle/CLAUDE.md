# lifecycle — the engine's start

`CgEngineLifecycle`: what core loads and starts as the game initialises, on both sides, in pre-init, init and
post-init, so a host only says when each phase is. Pre-init mounts every mod's assets (`asset.load.CgAssetMounts`),
init installs the network (`net.CgNetwork`), post-init (the engine's own, at its first client graphics start or server
start) loads what was mounted.

- **Core's starting work goes here**, never into a host: a host registers the platform, fills its slots, and calls the
  phases (`docs/MINECRAFT_INTEGRATION.md`).
- Graphics, which need a context, are `CgGraphicsLifecycle`'s, beside it.
- Code outside the engine hears both through one `CgLifecycleListener`: `onEnginePreInit`/`onEngineInit`/
  `onEnginePostInit`, then `onInit`/`onFrame`/`onDestroy`/`onShutdown`. Either `addListener` registers for all of
  them, and a late listener is caught up, so each fires once. A listener's phase runs after the engine's own work in it.
