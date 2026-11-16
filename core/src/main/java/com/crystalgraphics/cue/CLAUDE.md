# cue — gameplay cues

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

What a gameplay moment plays, routed by tag. Usage: `docs/vfx/networking.md` § *Cues*. Mirrors GAS's
`UGameplayCueNotify_Burst`, `AGameplayCueNotify_Looping` and `GameplayCueNotifyTypes`.

| Type | Role |
|---|---|
| `CgCueDefinition` | The compiled `cue` asset: phases as effects blocks, events, `writes` bound to user parameters, the networked settings; its Java `Builder` writes the file's tree |
| `CgCueCompiler` | The kind's compiler, registered by `CgBuiltInCompilers` on both sides |
| `CgCueManager` | A world's router in `PLAYS` (`UGameplayCueManager`, `UGameplayCueSet`): tag to cue with parent fallback, the queue, bursts, pooled looping plays, spawn conditions |
| `CgCuePlaying`, `CgCueParameters` | A looping play's handle (`writes` providers, events, removal); what a cue is played with (`FGameplayCueParameters`), and the `writes` values a play starts with (a burst's per-hit values, sent in the cue's `write.<name>.*` fields with their `set` flags) |
| `CgCueEffects` | The seam a client fills to draw and sound: `vfx.system.CgVfxCueEffects` plays the systems |
| `CgCueNet` | Every cue on the wire: a type per cue, `<ns>:cue/<path>`, declared as the cue compiles (`CgCueDefinition.COMPILER`) on each side; the parameters every play carries, then four floats and a `set` flag per `writes` name and the cue's own `fields`, by name; the client view and the prediction rule applied to every cue's type; the replicas (`interpolate`, `goal`, `snap`) |
| `CgCueInspector` | A dev tool's transport over the last burst the client played, its sequences and its particles' systems: pause, step a frame either way (seeks keep seeds), refire, HUD lines. Told by the manager's two hooks, `started` and `played`, and by the effects' `spawned` (a pooled `Part` a system), only once asked for |

- **Placement and spawn condition override whole**, entry over block over the cue's defaults, as
  `FGameplayCueNotify_SpawnContext::GetPlacementInfo`; unwritten fields take Unreal's constructor defaults
  (`do_not_attach`, `keep_world`, the instigator, always).
- **Routes come from the registry's gathered `tag`**, never from loading every cue; `route` adds a Java cue.
- **A net type per cue, declared before anyone connects**: every cue compiles in the startup load on both sides, so
  its type is in each hello. The parameters every play carries are one fixed set, as GAS's `FGameplayCueParameters`;
  the cue's `writes` and `fields` follow, with no cap. A reload that changes them fails by the cue's id until a
  restart. An object's cue is its type's (`CgCueNet.cueOf`).
- **Everything goes through the queue**, in order: a remove sent before its cue is ready waits behind the add.
- **Nothing allocates once warm**: commands, handles and each cue's `Lasting`s are pooled (`CgCueManagerTest`'s
  300 bursts a second). A new one is made only past a pool's peak; `prewarm` grows it ahead.
- **Headless**: a cue compiles and routes on a dedicated server, where only authoritative cues play, with no
  effects. Tests: `CgCueDefinitionTest` (`-Pkits` runs every kit cue), `CgCueManagerTest`, `CgCueNetTest` (the rig; `-Pkits=novamod=...` runs the
  Nova Cannon's), all `:core:headlessTest`.
