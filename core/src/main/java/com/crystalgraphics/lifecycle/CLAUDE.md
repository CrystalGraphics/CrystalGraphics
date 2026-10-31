# lifecycle — the engine's start

`CgEngineLifecycle`: what core loads and starts as the game initialises, on both sides, in pre-init, init and
post-init, so a host only says when each phase is. Pre-init mounts every mod's assets (`asset.load.CgAssetMounts`),
init installs the network (`net.CgNetwork`), post-init (the engine's own, at its first client graphics start or server
start) loads what was mounted.

- **Core's starting work goes here**, never into a host: a host registers the platform, fills its slots, and calls the
  phases (`docs/MINECRAFT_INTEGRATION.md`).
- Graphics, which need a context, are `CgGraphicsLifecycle`'s, beside it; features hear it through
  `CgLifecycleListener`.
