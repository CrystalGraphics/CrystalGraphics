# Glossary

| Word | Means | Taught in |
|---|---|---|
| **Actor** | what draws an entity with the engine's meshes and animation, in place of its vanilla model | [`../animation/guides/actors.md`](../animation/guides/actors.md) |
| **Authority** | the side whose word is the truth: the server | [`concepts/sides-and-authority.md`](concepts/sides-and-authority.md) |
| **Behaviour** | code a property type runs per object: each server tick (`onServer`), each client frame (`onClient`) | [`guides/properties.md`](guides/properties.md) |
| **Cue** | what plays when gameplay says "this happened": particles, sounds, a shake, chosen by its tag | [`guides/the-gameplay-handle.md`](guides/the-gameplay-handle.md) |
| **Entity type id** | `minecraft:zombie`, `mymod:wyvern`, `minecraft:player`: the same on every version | [`guides/properties.md`](guides/properties.md) |
| **Event** (motion) | a motion field true for the one tick or frame something happens in: `JUMPED`, `LANDED`, `HURT` | [`guides/what-it-is-doing.md`](guides/what-it-is-doing.md) |
| **Event** (network) | a moment the server sends to the clients that see an entity: `CgNetEvent` | [`guides/properties.md`](guides/properties.md) |
| **Gameplay handle** | `CgEntities.of(entity)`: an entity's tags, cues and its side's rights | [`guides/the-gameplay-handle.md`](guides/the-gameplay-handle.md) |
| **Local field** | a field each side keeps for itself, never sent or saved | [`guides/properties.md`](guides/properties.md) |
| **Motion** | `CgEntityProperties.MOTION`: what the engine works out of every entity | [`guides/what-it-is-doing.md`](guides/what-it-is-doing.md) |
| **Move lock** | a hold on an entity's own movement (keys, AI, gravity) while a move plays | [`guides/moving-an-entity.md`](guides/moving-an-entity.md) |
| **Network id** | the number the game knows an entity by on both sides for a session: what a client names it by | [`guides/properties.md`](guides/properties.md) |
| **Owner** | the player's own client, over that player: the one client whose changes the server may take | [`concepts/sides-and-authority.md`](concepts/sides-and-authority.md) |
| **Persisted** | saved: with the entity, or in a player's file | [`guides/saving.md`](guides/saving.md) |
| **Prediction** | the owner showing a change at once, before the server takes or refuses it | [`concepts/sides-and-authority.md`](concepts/sides-and-authority.md) |
| **Prediction key** | what groups one input's predicted changes, taken or refused together | [`guides/the-gameplay-handle.md`](guides/the-gameplay-handle.md) |
| **Property type** | a networked type on entities (`onEntities`): one object per entity | [`guides/properties.md`](guides/properties.md) |
| **Side** | the server, or a client | [`concepts/sides-and-authority.md`](concepts/sides-and-authority.md) |
| **Tag** | a named, counted mark on an entity, `mymod:state.stunned`, including the tags under it | [`guides/tags.md`](guides/tags.md) |
