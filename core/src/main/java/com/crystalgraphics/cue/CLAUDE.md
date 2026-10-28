# cue — gameplay cues

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

What a gameplay moment plays, routed by tag. Usage: `docs/vfx/networking.md` § *Cues*. Mirrors GAS's
`UGameplayCueNotify_Burst`, `AGameplayCueNotify_Looping` and `GameplayCueNotifyTypes`.

| Type | Role |
|---|---|
| `CgCueDefinition` | The compiled `cue` asset: phases as effects blocks, events, `writes` bound to user parameters, the networked settings; its Java `Builder` writes the file's tree |
| `CgCueCompiler` | The kind's compiler, registered by `CgBuiltInCompilers` on both sides |
| `CgCueManager` | A world's router in `PLAYS` (`UGameplayCueManager`, `UGameplayCueSet`): tag to cue with parent fallback, the queue, bursts, pooled looping plays, spawn conditions |
| `CgCuePlaying`, `CgCueParameters` | A looping play's handle (`writes` providers, events, removal); what a cue is played with (`FGameplayCueParameters`) |
| `CgCueEffects` | The seam a client fills to draw and sound: `vfx.system.CgVfxCueEffects` plays the systems |

- **Placement and spawn condition override whole**, entry over block over the cue's defaults, as
  `FGameplayCueNotify_SpawnContext::GetPlacementInfo`; unwritten fields take Unreal's constructor defaults
  (`do_not_attach`, `keep_world`, the instigator, always).
- **Routes come from the registry's gathered `tag`**, never from loading every cue; `route` adds a Java cue.
- **Everything goes through the queue**, in order: a remove sent before its cue is ready waits behind the add.
- **Nothing allocates once warm**: commands, handles and each cue's `Lasting`s are pooled (`CgCueManagerTest`'s
  300 bursts a second). A new one is made only past a pool's peak; `prewarm` grows it ahead.
- **Headless**: a cue compiles and routes on a dedicated server, where only authoritative cues play, with no
  effects. Tests: `CgCueDefinitionTest` (`-Pkits` runs every kit cue), `CgCueManagerTest`, both `:core:headlessTest`.
