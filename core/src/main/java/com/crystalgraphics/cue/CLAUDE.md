# cue — gameplay cues

> Root guide: [`CrystalGraphics/AGENTS.md`](../../../../../../../AGENTS.md)

What a gameplay moment plays, routed by tag. Usage: `docs/vfx/networking.md` § *Cues*. Mirrors GAS's
`UGameplayCueNotify_Burst`, `AGameplayCueNotify_Looping` and `GameplayCueNotifyTypes`.

| Type | Role |
|---|---|
| `CgCueDefinition` | The compiled `cue` asset: phases as effects blocks, events, `writes` bound to user parameters, the networked settings; its Java `Builder` writes the file's tree |
| `CgCueCompiler` | The kind's compiler, registered by `CgBuiltInCompilers` on both sides |

- **Placement and spawn condition override whole**, entry over block over the cue's defaults, as
  `FGameplayCueNotify_SpawnContext::GetPlacementInfo`; unwritten fields take Unreal's constructor defaults
  (`do_not_attach`, `keep_world`, the instigator, always).
- **The definition is the contract, never a playing cue**: nothing here plays or holds per-play state.
- **Headless**: a cue compiles on a dedicated server, its systems unresolved there. Tests: `CgCueDefinitionTest`
  (`:core:headlessTest`; `-Pkits` runs every kit cue).
