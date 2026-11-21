# Actors: your model on a Minecraft entity

**What you will have:** a zombie, a mob of your own or the player drawn with your skinned mesh and animated by your
graph, in place of its vanilla model, on every Minecraft version and loader. It walks, falls, swims and swings as the
entity does, holds its items, and reacts to being hit.

## In plain words

Minecraft draws a zombie with its own blocky model. An **actor** draws your model in its place and moves it the way
the zombie moves, like a costume worn by the mob. The game still runs the zombie (its walking, its attacks); the actor
only changes how it looks and animates.

An **actor** is what draws one entity: made as the entity appears, changed every frame, removed with it.

## The code

The common case needs no Java at all. One file:

```yaml
# assets/mymod/actors/golem.yaml
_v: 1
binds: { entities: [ mymod:golem ] }        # every golem gets this actor
prefab: mymod:golem                         # what it draws: a prefab with a skeletal mesh
anim_graph: mymod:golem_locomotion          # what its body plays
```

Every file under `actors/` registers itself as the game starts, on both sides. Its graph is fed by the entity every
frame: speed, ground, swimming, hands ([`entity-variables.md`](entity-variables.md)).

The same in Java:

```java
CgEntityActors.register("mymod:golem", CgEntityActors.plain())
        .prefab("mymod:golem")
        .graph("mymod:golem_locomotion");
```

Register once, at start-up, in common code: only entities that enter after it get an actor.

## Variations

### An actor with code of its own

Subclass `CgEntityActor` for what a file cannot say. `onSpawn` builds it, `tick` changes it each frame:

```java
public final class DragonActor extends CgEntityActor {
    private CgSkeletalMeshComponent body;

    @Override protected void onSpawn(CgEntity dragon) {
        body = add(scene().skeletal(DRAGON_MESH).materials(DRAGON_SKINS::get));
        body.anim().play(IDLE);
    }

    @Override protected void tick(CgEntity dragon, float dt) {
        CgNetObject motion = dragon.properties(CgEntityProperties.MOTION);
        body.scalar("Glow", motion.get(CgEntityProperties.SPRINTING) ? 2f : 0f);   // its scales light up at a sprint
        if (dragon.hasTag(ENRAGED)) body.vector("_Tint", 1f, 0.3f, 0.2f, 1f);
    }
}

CgEntityActors.register("mymod:dragon", DragonActor::new);
```

- **`add`** attaches a component to the actor's root, spawns it, and removes it with the actor.
- **The root** stands at the entity's feet, turned by its body's yaw: a mesh facing +z faces where the entity faces.
- **With a prefab** (`.prefab("mymod:wyvern")`), the prefab spawns on the root before `onSpawn`, and the code keeps
  only behaviour: `prefab().mesh("wings")`, `prefab().node("jaw")`.

### The player

```yaml
binds: { local_player: true }               # this client's own player
```

A file with no `binds` draws nothing until something binds it. In game, `/cgc actor golem` wears it (the id's last
part); from code:

```java
CgEntityActors.Registration hero = CgActorFiles.bindLocalPlayer("mymod:hero");
hero.close();                                // your own model again
```

### Beside the vanilla model

```java
CgEntityActors.register("mymod:golem_aura", AuraActor::new).keepVanillaModel();   // the game still draws the golem
```

In a file: `keep_vanilla_model: true`. Use it for something added to a vanilla mob (an aura, a hat) rather than a
replacement.

### An outline

```yaml
outline: { render_custom_depth: true, custom_depth_stencil_value: 1, post: mymod:renderer/outline }
```

The body writes stencil 1, and the engine's outline is drawn round it while any such actor lives: in `post`'s look,
else the engine's own, black. In Java: `.outline(1, "mymod:renderer/outline")`.

### Everything else an actor file says

| Key | Is | Guide |
|---|---|---|
| `binds` | `{ entities: [ids] }` or `{ local_player: true }` | above |
| `prefab` | what it draws | [`../../vfx/prefabs.md`](../../vfx/prefabs.md) |
| `anim_graph` | the graph its body runs | [`graphs-and-variables.md`](graphs-and-variables.md) |
| `root_motion` | the prefab node whose montages carry the entity | [`root-motion-moves-the-entity.md`](root-motion-moves-the-entity.md) |
| `reactions` | an event plays a sequence on a slot | [`reactions-and-locks.md`](reactions-and-locks.md) |
| `item_layers` | a held item gives a linked layer its graph | [`held-items-and-item-layers.md`](held-items-and-item-layers.md) |
| `keep_vanilla_model` | draw beside the game's model | above |
| `outline` | an outline round its body | above |

### On the server too

A registered actor spawns on the server as well, from the same class, for each entity of its type. There nothing is
drawn: its skeletal meshes are posed each tick for their bones, sockets and root motion, and its reactions play so a
move's root motion carries the entity. `server()` tells the two copies apart where your code must differ:

```java
@Override protected void tick(CgEntity golem, float dt) {
    if (server()) return;                    // the glow is a client's business
    body.scalar("Glow", ...);
}
```

From gameplay code, the server's actor of an entity is `CgEntityActors.serverActor(entityId)`, and its body's moves are
`actor.play("mymod:golem_slam")` ([`../../entities/guides/the-gameplay-handle.md`](../../entities/guides/the-gameplay-handle.md)).

## What goes wrong

- **Registered too late**: an entity that entered before `register` keeps its vanilla model. Register at start-up.
- **The entity type id** is the registry key on every version: `minecraft:zombie`, `mymod:wyvern`, and
  `minecraft:player` for every player. Below 1.11 the engine turns Forge's names into the same form.
- **An actor that throws is removed**, its entity left to the game's model, and the error logged.
- **A prefab that fails to load fails the spawn**: the entity is drawn by the game, and the log names the prefab.
- **Forge 1.13.2 and 1.14.3** load no mixins, so an actor draws beside the vanilla model there.
- **An actor file fails at start-up, naming itself**, for a prefab, graph or sequence no file declares, an unknown
  event, a rule naming a property no entity has, `binds` with both forms, or an entity type two actors bind.

## Where next

- The variables an entity feeds its graph: [`entity-variables.md`](entity-variables.md).
- A body with no graph of its own: the humanoid template, [`vanilla-models.md`](vanilla-models.md).
- A full walkthrough: [`../start/7-a-minecraft-mob-that-moves-like-your-character.md`](../start/7-a-minecraft-mob-that-moves-like-your-character.md).
